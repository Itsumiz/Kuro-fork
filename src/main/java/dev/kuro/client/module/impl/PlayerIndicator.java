package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.ShaderUi;
import dev.kuro.client.util.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Player Indicator: a ring of small arrows around your crosshair, one per player, each pointing the way that player is
 * (in front, to the side or behind you), with the name and distance beside it. The arrows are glowing notched arrowheads
 * (halo + lit body + bright core, settings "Glow %" and "Pulse"). Optional "Head markers" also float a
 * bobbing pointer above each head. Friends use the friend colour; Anti Bot bots are skipped.
 */
public class PlayerIndicator extends Module {
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.56f).wheel());
    private final Setting.Num size = add(new Setting.Num("Size", 7, 4, 14, 1));
    private final Setting.Num range = add(new Setting.Num("Range", 100, 10, 256, 5));
    private final Setting.Bool names = add(new Setting.Bool("Names", true));
    private final Setting.Bool distance = add(new Setting.Bool("Distance", true));
    private final Setting.Bool healthColor = add(new Setting.Bool("Colour by health", false));
    private final Setting.Bool bob = add(new Setting.Bool("Bobbing", true));
    private final Setting.Num glow = add(new Setting.Num("Glow %", 70, 0, 100, 5));             // soft halo around every arrow (0 = flat arrow)
    private final Setting.Bool pulse = add(new Setting.Bool("Pulse", true));                    // the glow breathes slowly
    private final Setting.Bool crosshair = add(new Setting.Bool("Crosshair arrows", true));    // arrows in a ring around the crosshair
    private final Setting.Num radius = add(new Setting.Num("Ring radius", 55, 25, 160, 5));
    private final Setting.Bool headMarker = add(new Setting.Bool("Head markers", false));      // pointer above the head
    private final Setting.Bool invisible = add(new Setting.Bool("Invisible", true));
    private final Setting.Bool friends = add(new Setting.Bool("Friends", true));

    private final double[] pt = new double[2];
    private final double[] xs = new double[3], ys = new double[3];

    public PlayerIndicator() { super("Player Indicator", Category.RENDER); }

    @Override
    public void onHud(GuiGraphics g) {
        if (mc.level == null || mc.player == null || mc.screen != null) return;
        boolean cam = Freecam.active();
        double max2 = range.value * range.value;
        double w = Projector.width(), h = Projector.height();
        float pt0 = Projector.tickProgress;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player && !cam) continue;
            if (p.isSpectator() || !p.isAlive() || AntiBot.isBot(p)) continue;
            if (!invisible.value && p.isInvisible()) continue;
            boolean friend = Friends.is(p);
            if (friend && !friends.value) continue;
            Vec3 pos = p.getPosition(pt0);
            double dx = pos.x - Projector.camPos.x, dy = pos.y - Projector.camPos.y, dz = pos.z - Projector.camPos.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > max2) continue;
            double dist = Math.sqrt(d2);
            int rgb = friend ? (Friends.argb() & 0xFFFFFF) : healthColor.value ? healthRgb(p) : color.rgb();
            int col = 0xFF000000 | rgb;
            int s = size.intValue();
            double wobble = bob.value ? Math.sin((mc.level.getGameTime() + pt0) * 0.18 + p.getId()) * 0.06 : 0.0;
            double headY = pos.y + p.getBbHeight() + 0.55 + wobble;

            if (crosshair.value) ringArrow(g, pos.x, pos.y + p.getBbHeight() * 0.5, pos.z, col, s, dist, p.getName().getString());
            if (headMarker.value && Projector.project(pos.x, headY, pos.z, pt)
                    && pt[0] > -20 && pt[0] < w + 20 && pt[1] > -20 && pt[1] < h + 20) {
                // pointer: a downward triangle whose tip sits on the projected point, shrinking a little with distance
                double k = Mth.clamp(1.25 - dist / 90.0, 0.65, 1.25);
                double hw = s * k, hh = s * 1.15 * k;
                glowArrow(g, pt[0], pt[1] - hh * 0.75, 0.0, 1.0, col, (int) Math.max(4, Math.round(hw)));
                if (names.value || distance.value) {
                    String text = (names.value ? p.getName().getString() : "")
                            + (names.value && distance.value ? "  " : "") + (distance.value ? Math.round(dist) + "m" : "");
                    Look.tagClamped(g, mc.font, text, (int) pt[0], (int) (pt[1] - hh - 17), col);
                }
            }
        }
    }

    /**
     * A glowing arrowhead (a notched chevron, tip first) centred on (cx, cy) pointing along (vx, vy): a soft halo behind it,
     * a body in the player's colour lifted towards white, a brighter core and a light edge, so it reads like a lit-up marker.
     */
    private void glowArrow(GuiGraphics g, double cx, double cy, double vx, double vy, int col, int s) {
        double nx = -vy, ny = vx, a = s;
        float amt = (float) glow.value / 100f;
        float breathe = pulse.value ? 0.82f + 0.18f * (float) Math.sin((mc.level.getGameTime() + Projector.tickProgress) * 0.14) : 1f;
        int rgb = col & 0xFFFFFF;

        // halo: two soft lights, a wide dim one and a tighter brighter one
        if (amt > 0f) {
            ShaderUi.spot(g, (float) cx, (float) cy, (float) (a * 3.6), (float) (a * 3.6), 14, Look.withAlpha(rgb, Math.round(0x70 * amt * breathe)));
            ShaderUi.spot(g, (float) cx, (float) cy, (float) (a * 2.0), (float) (a * 2.0), 34, Look.withAlpha(rgb, Math.round(0xB8 * amt * breathe)));
        }

        // shape: tip, right wing, notch, left wing
        double tipX = cx + vx * a * 1.3, tipY = cy + vy * a * 1.3;
        double rwX = cx - vx * a * 0.8 + nx * a * 0.95, rwY = cy - vy * a * 0.8 + ny * a * 0.95;
        double lwX = cx - vx * a * 0.8 - nx * a * 0.95, lwY = cy - vy * a * 0.8 - ny * a * 0.95;
        double ntX = cx - vx * a * 0.2, ntY = cy - vy * a * 0.2;

        int body = Look.withAlpha(Ui.mix(0xFF000000 | rgb, 0xFFFFFFFF, 0.28f), 0xF0);
        poly(g, tipX, tipY, rwX, rwY, ntX, ntY, body);
        poly(g, tipX, tipY, ntX, ntY, lwX, lwY, body);

        // core: a smaller, near-white copy of the shape, a little ahead of centre
        double k = 0.52, ox = vx * a * 0.18, oy = vy * a * 0.18;
        double ctX = cx + ox + (tipX - cx) * k, ctY = cy + oy + (tipY - cy) * k;
        double crX = cx + ox + (rwX - cx) * k, crY = cy + oy + (rwY - cy) * k;
        double clX = cx + ox + (lwX - cx) * k, clY = cy + oy + (lwY - cy) * k;
        double cnX = cx + ox + (ntX - cx) * k, cnY = cy + oy + (ntY - cy) * k;
        int core = Look.withAlpha(Ui.mix(0xFF000000 | rgb, 0xFFFFFFFF, 0.8f), 0xE0);
        poly(g, ctX, ctY, crX, crY, cnX, cnY, core);
        poly(g, ctX, ctY, cnX, cnY, clX, clY, core);

        // light edge round the outline (Gfx.line adds its own faint glow)
        int edge = Look.withAlpha(Ui.mix(0xFF000000 | rgb, 0xFFFFFFFF, 0.6f), 0xC0);
        Gfx.line(g, tipX, tipY, rwX, rwY, 1, edge);
        Gfx.line(g, rwX, rwY, ntX, ntY, 1, edge);
        Gfx.line(g, ntX, ntY, lwX, lwY, 1, edge);
        Gfx.line(g, lwX, lwY, tipX, tipY, 1, edge);
    }

    private void poly(GuiGraphics g, double ax, double ay, double bx, double by, double cx, double cy, int col) {
        xs[0] = ax; ys[0] = ay; xs[1] = bx; ys[1] = by; xs[2] = cx; ys[2] = cy;
        Gfx.fillPolygon(g, xs, ys, 3, col);
    }

    /** Filled triangle with a thin darker outline so it reads on any background. */
    private void triangle(GuiGraphics g, double ax, double ay, double bx, double by, double cx, double cy, int col) {
        xs[0] = ax; ys[0] = ay; xs[1] = bx; ys[1] = by; xs[2] = cx; ys[2] = cy;
        Gfx.fillPolygon(g, xs, ys, 3, Look.withAlpha(col, 0xE6));
        int edge = Look.withAlpha(0x101018, 0xB0);
        Gfx.line(g, ax, ay, bx, by, 1, edge);
        Gfx.line(g, bx, by, cx, cy, 1, edge);
        Gfx.line(g, cx, cy, ax, ay, 1, edge);
    }

    /**
     * An arrow on a ring around the crosshair, pointing toward the player (behind you or off screen too). When the player
     * is already on screen and close to the crosshair the arrow would only be in the way, so it is skipped.
     */
    private void ringArrow(GuiGraphics g, double x, double y, double z, int col, int s, double dist, String name) {
        double cx = Projector.width() / 2.0, cy = Projector.height() / 2.0, r = radius.value;
        boolean visible = Projector.project(x, y, z, pt);
        if (visible && Math.hypot(pt[0] - cx, pt[1] - cy) < r + s * 2.5) return;
        Projector.projectOrPoint(x, y, z, pt);
        double vx = pt[0] - cx, vy = pt[1] - cy, len = Math.hypot(vx, vy);
        if (len < 1e-3) { vx = 0; vy = 1; len = 1; }
        vx /= len; vy /= len;
        double px = cx + vx * r, py = cy + vy * r;           // arrow centre on the ring
        glowArrow(g, px, py, vx, vy, col, s);
        if (names.value || distance.value) {
            String text = (names.value ? name : "") + (names.value && distance.value ? "  " : "") + (distance.value ? Math.round(dist) + "m" : "");
            double lx = cx + vx * (r + s * 2.0 + 6), ly = cy + vy * (r + s * 2.0 + 6);
            // the tag sits just outside the arrow; push it sideways by its own half width so it never covers the arrow
            int half = (SdfTextW(text) + 14) / 2;
            Look.tagClamped(g, mc.font, text, (int) (lx + vx * half), (int) (ly + vy * 7) - 6, col);
        }
    }

    private static int SdfTextW(String t) { return dev.kuro.client.util.SdfText.w(t); }

    private static int healthRgb(AbstractClientPlayer p) {
        float f = Mth.clamp((p.getHealth() + p.getAbsorptionAmount()) / Math.max(1f, p.getMaxHealth()), 0f, 1f);
        int r = (int) (255 * Math.min(1f, (1f - f) * 2f)), gr = (int) (255 * Math.min(1f, f * 2f));
        return (r << 16) | (gr << 8) | 0x40;
    }
}
