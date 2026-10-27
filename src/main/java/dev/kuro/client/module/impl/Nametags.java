package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Nametags: one compact plate above every player with the name, a health bar (with a trailing "damage" shadow and a gold
 * absorption strip), the exact health, optional armour row, and the real-world (IRL) clock time.
 * Replaces the vanilla name tag while it is on (see NametagMixin).
 */
public class Nametags extends Module {
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    private static final DateTimeFormatter T24 = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter T24S = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter T12 = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter T12S = DateTimeFormatter.ofPattern("h:mm:ss a");
    private static final int ABSORB = 0xFFFFD84A;
    private static final int TRAIL = 0xFFE9EDF5;

    private static Nametags instance;

    private final Setting.Num range = add(new Setting.Num("Range", 128, 16, 256, 8));
    private final Setting.Bool showName = add(new Setting.Bool("Name", true));
    private final Setting.Bool ping = add(new Setting.Bool("Ping", false));
    private final Setting.Bool distance = add(new Setting.Bool("Distance", false));
    private final Setting.Bool healthBar = add(new Setting.Bool("Health Bar", true));
    private final Setting.Bool healthText = add(new Setting.Bool("Health Text", true));
    private final Setting.Bool armor = add(new Setting.Bool("Armor", true));
    private final Setting.Bool held = add(new Setting.Bool("Held Items", false));
    private final Setting.Bool irlTime = add(new Setting.Bool("IRL Time", true));
    private final Setting.Bool clock24 = add(new Setting.Bool("24h Clock", true));
    private final Setting.Bool seconds = add(new Setting.Bool("Seconds", false));
    private final Setting.Bool hideVanilla = add(new Setting.Bool("Hide Vanilla Tags", true));

    private final double[] o = new double[2];
    private final Map<UUID, Float> shown = new HashMap<>();   // lagging health, drawn as the white "damage" trail
    private final Set<UUID> seen = new HashSet<>();
    private String timeText = "";

    public Nametags() {
        super("Nametags", Category.RENDER);
        instance = this;
    }

    /** NametagMixin asks this: should the vanilla floating name be hidden for this entity? */
    public static boolean hidesVanilla(Entity e) {
        return instance != null && instance.isEnabled() && instance.hideVanilla.value && e instanceof Player;
    }

    @Override
    protected void onDisable() { shown.clear(); seen.clear(); }

    @Override
    public void onHud(GuiGraphics g) {
        boolean cam = Freecam.active();
        Vec3 c = Projector.camPos;
        double max2 = range.value * range.value;

        timeText = irlTime.value ? currentTime() : "";     // formatted once per frame, not per player
        seen.clear();

        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player && !cam) continue;
            if (p.isSpectator()) continue;
            Vec3 pos = p.getPosition(Projector.tickProgress);
            double dx = pos.x - c.x, dy = pos.y - c.y, dz = pos.z - c.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            if (!Projector.project(pos.x, pos.y + p.getBbHeight() + 0.4, pos.z, o)) continue;
            seen.add(p.getUUID());
            draw(g, p, (int) o[0], (int) o[1]);
        }
        shown.keySet().retainAll(seen);
    }

    private String currentTime() {
        LocalTime now = LocalTime.now();
        DateTimeFormatter f = clock24.value ? (seconds.value ? T24S : T24) : (seconds.value ? T12S : T12);
        return now.format(f);
    }

    private void draw(GuiGraphics g, Player p, int cx, int bottom) {
        float hp = p.getHealth(), max = Math.max(1f, p.getMaxHealth()), abs = p.getAbsorptionAmount();
        float frac = Mth.clamp(hp / max, 0f, 1f);
        int accent = Look.health(frac);
        boolean friend = Friends.is(p);

        // ---- text pieces
        String nm = showName.value ? p.getName().getString() : "";
        String pg = "", ds = "";
        if (ping.value && mc.getConnection() != null) {
            net.minecraft.client.multiplayer.PlayerInfo e = mc.getConnection().getPlayerInfo(p.getUUID());
            if (e != null && e.getLatency() > 0) pg = (nm.isEmpty() ? "" : "  ") + e.getLatency() + "ms";
        }
        if (distance.value) ds = (nm.isEmpty() && pg.isEmpty() ? "" : "  ") + Math.round(mc.player.distanceTo(p)) + "m";
        boolean nameRow = !(nm.isEmpty() && pg.isEmpty() && ds.isEmpty());

        String hs = "";
        if (healthText.value) {
            hs = String.format("%.1f", hp);
            if (abs > 0) hs += String.format(" +%.0f", abs);
        }
        String ts = timeText;
        String gap = (!hs.isEmpty() && !ts.isEmpty()) ? "   " : "";
        boolean infoRow = !hs.isEmpty() || !ts.isEmpty();

        int nameW = SdfText.w(nm) + SdfText.w(pg) + SdfText.w(ds);
        int infoW = SdfText.w(hs) + SdfText.w(gap) + SdfText.w(ts);
        int barW = Math.max(60, Math.max(nameW, infoW));
        int inner = barW;
        int plateW = inner + 14;

        int rows = (nameRow ? 12 : 0) + (healthBar.value ? 8 : 0) + (infoRow ? 11 : 0);
        if (rows == 0) return;
        int plateH = rows + 5;
        int y2 = bottom, y1 = y2 - plateH;
        int x1 = cx - plateW / 2, x2 = x1 + plateW;

        Look.panel(g, x1, y1, x2, y2, friend ? Friends.argb() : accent);
        int x = x1 + 8, y = y1 + 3;

        if (nameRow) {
            int nx = cx - nameW / 2 + 3;
            SdfText.ui(g, nm, nx, y, friend ? Friends.argb() : Look.TEXT, false);
            nx += SdfText.w(nm);
            if (!pg.isEmpty()) {
                int lat = parseLatency(pg);
                SdfText.ui(g, pg, nx, y, lat < 80 ? Look.GOOD : lat < 150 ? Look.WARN : Look.BAD, false);
                nx += SdfText.w(pg);
            }
            SdfText.ui(g, ds, nx, y, Look.MUTED, false);
            y += 12;
        }

        if (healthBar.value) {
            // lagging value: eases down after damage (white trail), snaps up on heal
            UUID id = p.getUUID();
            float prev = shown.getOrDefault(id, frac);
            float now = frac >= prev ? frac : prev + (frac - prev) * 0.12f;
            if (now - frac < 0.004f) now = frac;
            shown.put(id, now);

            int bx = x - 1, bw = inner + 0;
            if (now > frac) Look.bar(g, bx, y, bw, 5, now, TRAIL);
            Look.bar(g, bx, y, bw, 5, frac, accent);
            if (abs > 0) Look.bar(g, bx, y, bw, 2, Mth.clamp(abs / max, 0f, 1f), ABSORB);
            y += 8;
        }

        if (infoRow) {
            int ix = cx - infoW / 2 + 3;
            SdfText.ui(g, hs, ix, y, accent, false);
            ix += SdfText.w(hs) + SdfText.w(gap);
            SdfText.ui(g, ts, ix, y, Look.MUTED, false);
        }

        if (armor.value || held.value) drawItems(g, p, cx, y1 - 2);
    }

    private static int parseLatency(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** Armour (and optionally hand items) with a durability strip, in a row above the plate. */
    private void drawItems(GuiGraphics g, Player p, int cx, int bottom) {
        List<ItemStack> stacks = new ArrayList<>();
        if (armor.value) for (EquipmentSlot s : ARMOR) stacks.add(p.getItemBySlot(s));
        if (held.value) { stacks.add(p.getMainHandItem()); stacks.add(p.getOffhandItem()); }
        stacks.removeIf(ItemStack::isEmpty);
        if (stacks.isEmpty()) return;

        int slot = 20;
        int x = cx - stacks.size() * slot / 2 + (slot - 16) / 2;
        int y = bottom - 20;
        for (ItemStack st : stacks) {
            g.renderItem(st, x, y);
            if (st.isDamageableItem() && st.getMaxDamage() > 0) {
                float f = 1f - (float) st.getDamageValue() / st.getMaxDamage();
                Look.bar(g, x, y + 17, 16, 2, f, Look.health(f));
            }
            x += slot;
        }
    }
}
