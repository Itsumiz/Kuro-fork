package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Ballistics;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Pearl Finder: highlights every ender pearl in the air that somebody else threw. Each pearl gets a glowing box that
 * shows through walls, a tracer from your crosshair, a label with the thrower's name and the distance, and its
 * predicted flight (gravity 0.03, drag 0.99) ending in a landing marker, which is where that player will teleport to.
 * Your own pearls and your friends' pearls are skipped (friends only when "Friends" is off).
 */
public class PearlFinder extends Module {
    private final Setting.Num range = add(new Setting.Num("Range", 128, 16, 256, 8));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.83f).wheel());
    private final Setting.Hue landColor = add(new Setting.Hue("Landing color", 0.12f).wheel());
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 2, 1, 3, 1));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool fill = add(new Setting.Bool("Fill", true));
    private final Setting.Bool tracers = add(new Setting.Bool("Tracers", true));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", true));
    private final Setting.Bool path = add(new Setting.Bool("Predicted path", true));
    private final Setting.Bool landing = add(new Setting.Bool("Landing marker", true));
    private final Setting.Bool unknown = add(new Setting.Bool("Unknown owners", true));   // thrower not loaded on your client
    private final Setting.Bool friends = add(new Setting.Bool("Friends", false));

    private record Found(Vec3 pos, String owner, double dist, Ballistics.Result flight) {}

    private List<Found> found = List.of();
    private final double[] seg = new double[4];
    private final double[] pt = new double[2];

    public PearlFinder() { super("Pearl Finder", Category.RENDER); }

    @Override protected void onDisable() { found = List.of(); }

    /** True for a pearl that should be shown. owner[0] gets the display name. */
    private boolean enemy(Entity e, String[] owner) {
        if (!(e instanceof Projectile pr)) return false;
        Entity o = pr.getOwner();
        if (o == mc.player) return false;
        if (o == null) { owner[0] = "Unknown"; return unknown.value; }
        owner[0] = o.getName().getString();
        if (o instanceof Player && Friends.is(o) && !friends.value) return false;
        return true;
    }

    private void scan() {
        if (mc.level == null || mc.player == null) { found = List.of(); return; }
        List<Found> out = new ArrayList<>();
        Vec3 cam = Projector.camPos;
        double max2 = range.value * range.value;
        String[] owner = new String[1];
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getType() != EntityType.ENDER_PEARL || e.isRemoved()) continue;
            Vec3 pos = e.getPosition(Projector.tickProgress);
            double d2 = pos.distanceToSqr(cam);
            if (d2 > max2 || !enemy(e, owner)) continue;
            Ballistics.Result flight = null;
            if (path.value || landing.value) {
                Vec3 v = e.getDeltaMovement();
                if (v.lengthSqr() > 1.0E-4) flight = Ballistics.simulate(e, pos, v, 0.03, 0.99, 120, false);
            }
            out.add(new Found(pos, owner[0], Math.sqrt(d2), flight));
        }
        found = out;
    }

    @Override
    public void onWorld() {
        scan();
        if (found.isEmpty()) return;
        int rgb = color.rgb(), line = 0xFF000000 | rgb, shade = fill.value ? (0x50 << 24) | rgb : 0;
        int t = thickness.intValue();
        for (Found f : found) {
            Vec3 p = f.pos();
            double s = 0.25;      // a little bigger than the 0.25 pearl hitbox so it is easy to spot at range
            WorldGfx.box(p.x - s, p.y - s + 0.125, p.z - s, p.x + s, p.y + s + 0.125, p.z + s, line, shade, t, glow.value);
            if (landing.value && f.flight() != null) {
                Vec3 e = f.flight().end;
                int lr = landColor.rgb();
                double h = 0.3;
                WorldGfx.box(e.x - h, e.y, e.z - h, e.x + h, e.y + 2 * h, e.z + h, 0xFF000000 | lr, (0x50 << 24) | lr, t, glow.value);
            }
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        if (found.isEmpty() || mc.screen != null) return;
        int rgb = color.rgb(), line = Look.withAlpha(rgb, 0xDC), landLine = Look.withAlpha(landColor.rgb(), 0xDC);
        int t = thickness.intValue();
        double cx = Projector.width() / 2.0, cy = Projector.height() / 2.0;
        for (Found f : found) {
            Vec3 p = f.pos();
            if (tracers.value) {
                Projector.projectOrPoint(p.x, p.y, p.z, pt);
                Gfx.line(g, cx, cy, pt[0], pt[1], 1, Look.withAlpha(rgb, 0xB4));
            }
            Ballistics.Result fl = f.flight();
            if (path.value && fl != null) {
                for (int i = 1; i < fl.pts.size(); i++) {
                    Vec3 a = fl.pts.get(i - 1), b = fl.pts.get(i);
                    if (Projector.segment(a.x, a.y, a.z, b.x, b.y, b.z, seg)) Gfx.line(g, seg[0], seg[1], seg[2], seg[3], t, landLine);
                }
            }
            if (labels.value) {
                if (Projector.project(p.x, p.y + 0.6, p.z, pt))
                    Look.tag(g, mc.font, "Pearl  " + f.owner() + "  " + Math.round(f.dist()) + "m", (int) pt[0], (int) pt[1] - 7, line);
                if (landing.value && fl != null && Projector.project(fl.end.x, fl.end.y + 0.9, fl.end.z, pt))
                    Look.tag(g, mc.font, "Lands " + f.owner() + "  " + fl.ticks + "t", (int) pt[0], (int) pt[1] - 7, landLine);
            }
        }
    }
}
