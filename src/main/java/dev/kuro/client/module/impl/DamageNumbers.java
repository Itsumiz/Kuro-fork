package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Damage Numbers: a number pops up over anything that loses health (and optionally gains it), floats up, drifts
 * sideways and fades out. Purely visual, drawn client-side in the HUD layer through {@link Projector}.
 *
 * Damage is read from the change in health + absorption between two ticks, so it shows what the server actually
 * applied (armor, enchants and totems included) rather than a guess. A freshly loaded entity is ignored for its first
 * few ticks because its real health arrives a moment after it spawns on the client.
 * "Only my hits" shows numbers for entities you attacked in the last ~0.75 s (ClientPlayerInteractionManagerMixin
 * reports every attack to {@link #onAttack}); damage you take yourself always counts when "Include yourself" is on.
 */
public class DamageNumbers extends Module {
    private static final int MAX_POPS = 160;
    private static final int SETTLE_TICKS = 3;     // ignore health changes right after an entity first appears
    private static final int ATTACK_WINDOW = 15;   // ticks after your swing in which damage counts as "your hit"
    private static DamageNumbers instance;

    private final Setting.Num range = add(new Setting.Num("Range", 64, 8, 128, 4));
    private final Setting.Num life = add(new Setting.Num("Lifetime (ticks)", 28, 10, 60, 2));
    private final Setting.Num size = add(new Setting.Num("Size", 1.0, 0.5, 2.0, 0.1));
    private final Setting.Num rise = add(new Setting.Num("Rise (blocks)", 0.9, 0.2, 2.0, 0.1));
    private final Setting.Num minDamage = add(new Setting.Num("Min damage", 0.5, 0.1, 5.0, 0.1));
    private final Setting.Bool decimals = add(new Setting.Bool("Decimals", true));
    private final Setting.Bool byAmount = add(new Setting.Bool("Color by amount", true));   // white -> yellow -> red as hits get bigger
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.0f).wheel());
    private final Setting.Bool pop = add(new Setting.Bool("Pop-in", true));
    private final Setting.Bool shadow = add(new Setting.Bool("Shadow", true));
    private final Setting.Bool onlyMine = add(new Setting.Bool("Only my hits", false));
    private final Setting.Bool playersOnly = add(new Setting.Bool("Players only", true));
    private final Setting.Bool self = add(new Setting.Bool("Include yourself", false));
    private final Setting.Bool heal = add(new Setting.Bool("Show healing", false));

    private static final class Pop {
        final double x, y, z, drift;
        final float amount;
        final boolean healing;
        int age;
        Pop(double x, double y, double z, double drift, float amount, boolean healing) {
            this.x = x; this.y = y; this.z = z; this.drift = drift; this.amount = amount; this.healing = healing;
        }
    }

    private static final class Track {
        float total;
        int ticks;
        Track(float total) { this.total = total; }
    }

    private final List<Pop> pops = new ArrayList<>();
    private final Map<Integer, Track> tracks = new HashMap<>();
    private final Map<Integer, Long> attacked = new HashMap<>();
    private final Set<Integer> seen = new HashSet<>();
    private final double[] o = new double[2];

    public DamageNumbers() {
        super("Damage Numbers", Category.RENDER);
        instance = this;
    }

    /** Called by ClientPlayerInteractionManagerMixin at the start of every attack on {@code target}. */
    public static void onAttack(Entity target) {
        DamageNumbers m = instance;
        if (m == null || !m.isEnabled() || m.mc.level == null || target == null) return;
        m.attacked.put(target.getId(), m.mc.level.getGameTime());
    }

    @Override protected void onEnable() { clear(); }
    @Override protected void onDisable() { clear(); }

    private void clear() { pops.clear(); tracks.clear(); attacked.clear(); seen.clear(); }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) { clear(); return; }
        int lifetime = life.intValue();
        pops.removeIf(p -> ++p.age > lifetime);

        long now = mc.level.getGameTime();
        attacked.values().removeIf(t -> now - t > 40);

        double max2 = range.value * range.value;
        float min = (float) minDamage.value;
        seen.clear();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e instanceof ArmorStand) continue;
            boolean isSelf = le == mc.player;
            if (isSelf && !self.value) continue;
            if (playersOnly.value && !(le instanceof Player)) continue;
            if (le instanceof Player pl && pl.isSpectator()) continue;

            int id = le.getId();
            seen.add(id);
            float total = le.getHealth() + le.getAbsorptionAmount();
            Track t = tracks.get(id);
            if (t == null) { tracks.put(id, new Track(total)); continue; }

            float diff = total - t.total;
            t.total = total;
            if (++t.ticks <= SETTLE_TICKS) continue;
            if (Math.abs(diff) < min) continue;

            boolean healing = diff > 0;
            if (healing && (!heal.value || onlyMine.value)) continue;
            if (!healing && onlyMine.value && !isSelf) {
                Long at = attacked.get(id);
                if (at == null || now - at > ATTACK_WINDOW) continue;
            }
            if (pops.size() >= MAX_POPS) continue;
            if (mc.player.distanceToSqr(le) > max2) continue;

            ThreadLocalRandom r = ThreadLocalRandom.current();
            double half = le.getBbWidth() * 0.5;
            pops.add(new Pop(
                    le.getX() + (r.nextDouble() - 0.5) * half,
                    le.getY() + le.getBbHeight() * 0.85,
                    le.getZ() + (r.nextDouble() - 0.5) * half,
                    (r.nextDouble() - 0.5) * 0.7,          // sideways drift over the whole lifetime, in blocks
                    Math.abs(diff), healing));
        }
        tracks.keySet().retainAll(seen);
    }

    @Override
    public void onHud(GuiGraphics g) {
        if (pops.isEmpty() || mc.level == null) return;
        int lifetime = life.intValue();
        double riseBlocks = rise.value, sizeMul = size.value;
        int customRgb = color.rgb() & 0xFFFFFF;

        for (Pop p : pops) {
            float f = Mth.clamp((p.age + Projector.tickProgress) / lifetime, 0f, 1f);   // 0 = just spawned, 1 = gone
            double inv = 1.0 - f;
            double ease = 1.0 - inv * inv * inv;                                        // fast at first, then settles
            double x = p.x + p.drift * ease * Projector.rightX();
            double y = p.y + riseBlocks * ease;
            double z = p.z + p.drift * ease * Projector.rightZ();

            if (!Projector.project(x, y, z, o)) continue;
            double depth = Projector.depth(x, y, z);

            float alpha = f < 0.55f ? 1f : 1f - (f - 0.55f) / 0.45f;
            if (alpha <= 0.02f) continue;
            float scale = 1f;
            if (pop.value && f < 0.18f) { float k = 1f - f / 0.18f; scale = 1f + 0.55f * k * k; }   // starts big, snaps down

            float px = (float) Mth.clamp(0.30 * Projector.pixelsPerUnit(depth) * sizeMul, 7.0, 40.0) * scale;
            String s = format(p.amount, p.healing);
            int rgb = p.healing ? (Look.GOOD & 0xFFFFFF) : byAmount.value ? amountColor(p.amount) : customRgb;
            int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));

            float cx = (float) o[0], y0 = (float) o[1] - px * 0.5f;
            if (shadow.value) SdfText.centered(g, SdfText.Face.BOLD, s, cx + 1f, y0 + 1f, px, ((int) (a * 0.65f) << 24));
            SdfText.centered(g, SdfText.Face.BOLD, s, cx, y0, px, (a << 24) | rgb);
        }
    }

    private String format(float amount, boolean healing) {
        String n;
        if (decimals.value && amount < 10f) {
            double r = Math.round(amount * 10.0) / 10.0;
            n = r == Math.rint(r) ? Integer.toString((int) r) : Double.toString(r);
        } else {
            n = Integer.toString(Math.round(amount));
        }
        return healing ? "+" + n : n;
    }

    /** White for chip damage, yellow around 6 (3 hearts), red from 12 (6 hearts) up. */
    private static int amountColor(float amount) {
        int white = Look.TEXT & 0xFFFFFF, yellow = Look.WARN & 0xFFFFFF, red = Look.BAD & 0xFFFFFF;
        if (amount <= 6f) return lerp(white, yellow, amount / 6f);
        return lerp(yellow, red, Math.min(1f, (amount - 6f) / 6f));
    }

    private static int lerp(int a, int b, float f) {
        f = Mth.clamp(f, 0f, 1f);
        int r = (int) (((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = (int) (((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = (int) ((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return (r << 16) | (g << 8) | bl;
    }
}
