package dev.kuro.client.module.impl;

import dev.kuro.client.gui.UiThemes;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Jump Bubbles: when a player jumps, glowing rings spread out over the ground where they took off and fade away.
 * A jump is spotted when a player who was on the ground last tick is in the air this tick and has risen more than
 * 0.2 blocks (a normal jump rises 0.42 on its first tick). Purely visual, drawn client-side.
 * The rings are drawn through the world like the other ESP boxes (no depth test), so they also show behind walls.
 */
public class JumpBubbles extends Module {
    private final Setting.Bool followTheme = add(new Setting.Bool("Follow theme", true));   // on = rings use the UI theme's accent instead of Color
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.83f).wheel());
    private final Setting.Num rings = add(new Setting.Num("Rings", 2, 1, 3, 1));
    private final Setting.Num size = add(new Setting.Num("Size (blocks)", 1.6, 0.5, 4.0, 0.1));
    private final Setting.Num life = add(new Setting.Num("Lifetime (ticks)", 20, 8, 60, 2));
    private final Setting.Num range = add(new Setting.Num("Range", 48, 8, 128, 4));
    private final Setting.Bool bubble = add(new Setting.Bool("Filled bubble", true));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool self = add(new Setting.Bool("Include yourself", false));
    private final Setting.Bool friends = add(new Setting.Bool("Friends", true));   // off = friends don't make bubbles

    private static final int MAX_BUBBLES = 200;

    private static final class Bubble {
        final double x, y, z;
        final int rgb;
        int age;
        Bubble(double x, double y, double z, int rgb) { this.x = x; this.y = y; this.z = z; this.rgb = rgb; }
    }

    private final List<Bubble> bubbles = new ArrayList<>();
    private final Map<UUID, double[]> last = new HashMap<>();   // {y, wasOnGround (1/0)}

    public JumpBubbles() { super("Jump Bubbles", Category.RENDER); }

    @Override protected void onEnable() { bubbles.clear(); last.clear(); }
    @Override protected void onDisable() { bubbles.clear(); last.clear(); }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) { bubbles.clear(); last.clear(); return; }
        int lifetime = life.intValue();
        bubbles.removeIf(b -> ++b.age > lifetime);

        java.util.Set<UUID> seen = new java.util.HashSet<>();
        double max2 = range.value * range.value;
        for (AbstractClientPlayer p : mc.level.players()) {
            UUID id = p.getUUID();
            seen.add(id);
            double[] prev = last.get(id);
            boolean ground = p.onGround();
            if (prev != null && prev[1] == 1.0 && !ground && p.getY() - prev[0] > 0.2
                    && !p.isSpectator() && !p.isPassenger() && !p.isFallFlying() && !p.isSwimming()
                    && (p != mc.player || self.value)
                    && (friends.value || !Friends.is(p))
                    && p.distanceToSqr(mc.player) <= max2
                    && bubbles.size() < MAX_BUBBLES) {
                int rgb = Friends.is(p) ? (Friends.argb() & 0xFFFFFF) : followTheme.value ? (UiThemes.accent() & 0xFFFFFF) : color.rgb();
                bubbles.add(new Bubble(p.getX(), prev[0], p.getZ(), rgb));
            }
            if (prev == null) { prev = new double[2]; last.put(id, prev); }
            prev[0] = p.getY();
            prev[1] = ground ? 1.0 : 0.0;
        }
        last.keySet().retainAll(seen);
    }

    @Override
    public void onWorld() {
        if (bubbles.isEmpty()) return;
        int lifetime = life.intValue(), n = rings.intValue();
        double maxR = size.value, max2 = (range.value + 8) * (range.value + 8);
        Vec3 cam = Projector.camPos;
        for (Bubble b : bubbles) {
            double dx = b.x - cam.x, dy = b.y - cam.y, dz = b.z - cam.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            double t = Math.min(1.0, (b.age + Projector.tickProgress) / lifetime);   // 0 = just jumped, 1 = gone
            double ease = 1.0 - (1.0 - t) * (1.0 - t);                                  // fast at first, slows down
            double fade = 1.0 - t;
            double y = b.y + 0.02;                                                       // just above the floor
            if (bubble.value) WorldGfx.ring(b.x, y, b.z, 0.0, 0.25 + maxR * 0.75 * ease, argb(b.rgb, 0.16 * fade), 28);
            for (int i = 0; i < n; i++) {
                double delay = i * 0.22;                                                 // later rings start a little behind
                double ti = Math.min(1.0, Math.max(0.0, (t - delay) / (1.0 - delay)));
                if (ti <= 0.0) continue;
                double r = 0.2 + maxR * (1.0 - (1.0 - ti) * (1.0 - ti)) * (1.0 - 0.18 * i);
                double w = 0.045 + 0.02 * (1.0 - ti);
                double a = (1.0 - ti) * (1.0 - 0.15 * i);
                if (glow.value) WorldGfx.ring(b.x, y, b.z, Math.max(0, r - w * 3.2), r + w * 3.2, argb(b.rgb, 0.20 * a), 36);
                WorldGfx.ring(b.x, y, b.z, Math.max(0, r - w), r + w, argb(blend(b.rgb, 0xFFFFFF, 0.55f), 0.95 * a), 36);
            }
        }
    }

    private static int argb(int rgb, double alpha) {
        int a = (int) Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static int blend(int a, int b, float f) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = (int) (((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = (int) ((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return (r << 16) | (g << 8) | bl;
    }
}
