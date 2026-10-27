package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Ballistics;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projectile Trails: a glowing ribbon behind every arrow, pearl, trident, snowball, potion ... in flight, plus a
 * predicted path from the projectile to where it will land (with a landing ring, the time left and, when it will hit
 * somebody, a box round them). Everything is client-side and read from the entities you can already see.
 */
public class ProjectileTrails extends Module {
    private static final String[] COLORS = {"By type", "Solid", "Rainbow"};

    private final Setting.Bool arrows = add(new Setting.Bool("Arrows / bolts", true));
    private final Setting.Bool pearls = add(new Setting.Bool("Ender pearls", true));
    private final Setting.Bool tridents = add(new Setting.Bool("Tridents", true));
    private final Setting.Bool thrown = add(new Setting.Bool("Snowballs / eggs / potions", true));
    private final Setting.Bool onlyMine = add(new Setting.Bool("Only my projectiles", false));
    private final Setting.Bool trail = add(new Setting.Bool("Trail", true));
    private final Setting.Bool predict = add(new Setting.Bool("Predict landing", true));
    private final Setting.Num colorMode = add(new Setting.Num("Color mode", 0, 0, COLORS.length - 1, 1).labelled(COLORS));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.55f).wheel());
    private final Setting.Num length = add(new Setting.Num("Trail length (ticks)", 24, 6, 80, 2));
    private final Setting.Num width = add(new Setting.Num("Width (blocks)", 0.18, 0.04, 0.8, 0.02));
    private final Setting.Num opacity = add(new Setting.Num("Opacity %", 85, 10, 100, 5));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool landing = add(new Setting.Bool("Landing marker", true));
    private final Setting.Bool hitBox = add(new Setting.Bool("Box around who it hits", true));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", true));
    private final Setting.Bool depth = add(new Setting.Bool("Hide behind walls", false));
    private final Setting.Num range = add(new Setting.Num("Range", 96, 16, 192, 8));
    private final Setting.Num maxShown = add(new Setting.Num("Max projectiles", 12, 1, 40, 1));

    private enum Kind { ARROW, PEARL, TRIDENT, THROWN, OTHER }

    private record Sample(double x, double y, double z, long tick) {}

    private static final class Track {
        final ArrayDeque<Sample> pts = new ArrayDeque<>();   // newest first
        Kind kind = Kind.OTHER;
        long lastMoved;
        double lx, ly, lz;
        boolean has;
        Ballistics.Result path;                              // predicted flight, refreshed every tick
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    private long tick;
    private final double[] pt = new double[2];

    public ProjectileTrails() { super("Projectile Trails", Category.RENDER); }

    @Override protected void onEnable() { tracks.clear(); }
    @Override protected void onDisable() { tracks.clear(); }

    /** Which kind of projectile this is, from its entity-type name (null = not one we show). */
    private Kind kindOf(Entity e) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        switch (id) {
            case "arrow", "spectral_arrow": return arrows.value ? Kind.ARROW : null;
            case "ender_pearl": return pearls.value ? Kind.PEARL : null;
            case "trident": return tridents.value ? Kind.TRIDENT : null;
            case "snowball", "egg", "potion", "splash_potion", "lingering_potion", "experience_bottle", "wind_charge", "breeze_wind_charge":
                return thrown.value ? Kind.THROWN : null;
            default: return null;
        }
    }

    /** {gravity, drag} per tick, as in vanilla. */
    private static double[] physics(Entity e, Kind k) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        return switch (id) {
            case "wind_charge", "breeze_wind_charge" -> new double[]{0.0, 1.0};
            case "experience_bottle" -> new double[]{0.07, 0.99};
            case "potion", "splash_potion", "lingering_potion" -> new double[]{0.05, 0.99};
            case "snowball", "egg", "ender_pearl" -> new double[]{0.03, 0.99};
            default -> new double[]{0.05, 0.99};      // arrows, bolts, tridents
        };
    }

    private int rgbFor(Kind k, double t) {
        int mode = colorMode.intValue();
        if (mode == 1) return Look.rgb(color.hue);
        if (mode == 2) return Look.rgb((float) (((tick * 0.01) + t * 0.6) % 1.0));
        return switch (k) {
            case ARROW -> Look.rgb(0.08f);      // orange
            case PEARL -> Look.rgb(0.78f);      // purple
            case TRIDENT -> Look.rgb(0.5f);     // aqua
            case THROWN -> Look.rgb(0.33f);     // green
            default -> Look.rgb(color.hue);
        };
    }

    private static int argb(int rgb, double a) {
        return (Mth.clamp((int) Math.round(a * 255.0), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ simulation (once per tick)

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) { tracks.clear(); return; }
        tick++;
        double max2 = range.value * range.value;
        int cap = length.intValue();
        Set<Integer> seen = new HashSet<>();
        List<Entity> mine = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Projectile p) || e.isRemoved()) continue;
            Kind k = kindOf(e);
            if (k == null) continue;
            if (onlyMine.value && p.getOwner() != mc.player) continue;
            if (e.distanceToSqr(mc.player) > max2) continue;
            seen.add(e.getId());
            Track tr = tracks.computeIfAbsent(e.getId(), id -> new Track());
            tr.kind = k;
            double x = e.getX(), y = e.getY(), z = e.getZ();
            boolean moved = !tr.has || (x - tr.lx) * (x - tr.lx) + (y - tr.ly) * (y - tr.ly) + (z - tr.lz) * (z - tr.lz) > 1.0e-4;
            tr.lx = x; tr.ly = y; tr.lz = z; tr.has = true;
            if (moved) { tr.pts.addFirst(new Sample(x, y, z, tick)); tr.lastMoved = tick; }
            while (tr.pts.size() > cap + 2) tr.pts.removeLast();
            while (!tr.pts.isEmpty() && tick - tr.pts.peekLast().tick() > cap) tr.pts.removeLast();
            mine.add(e);
        }
        // projectiles that vanished (landed / despawned) keep their trail until it fades, then are dropped
        tracks.entrySet().removeIf(en -> !seen.contains(en.getKey()) && (en.getValue().pts.isEmpty() || tick - en.getValue().lastMoved > cap));
        for (Map.Entry<Integer, Track> en : tracks.entrySet()) if (!seen.contains(en.getKey())) en.getValue().path = null;

        // predicted flight: nearest few moving projectiles only
        if (!predict.value) { for (Track t : tracks.values()) t.path = null; return; }
        mine.sort((a, b) -> Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player)));
        int n = 0;
        for (Entity e : mine) {
            Track tr = tracks.get(e.getId());
            if (tr == null) continue;
            Vec3 v = e.getDeltaMovement();
            if (n >= maxShown.intValue() || tick - tr.lastMoved > 1 || v.lengthSqr() < 0.01) { tr.path = null; continue; }   // stuck in a block / standing still
            double[] ph = physics(e, tr.kind);
            Entity owner = e instanceof Projectile pr ? pr.getOwner() : null;      // your own arrow must not "hit" you at the start
            tr.path = Ballistics.simulate(owner != null ? owner : e, e.position(), v, ph[0], ph[1], 160, true);
            n++;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void onWorld() {
        if (!WorldGfx.active() || mc.level == null || mc.player == null || tracks.isEmpty()) return;
        WorldGfx.setDepthTest(depth.value);
        try {
            float pt = Projector.tickProgress;
            double op = opacity.value / 100.0;
            for (Map.Entry<Integer, Track> en : tracks.entrySet()) {
                Track tr = en.getValue();
                Entity e = mc.level.getEntity(en.getKey());
                if (trail.value) drawTrail(tr, e, pt, op);
                if (tr.path != null && tr.path.pts.size() > 1) drawPath(tr, e, pt, op);
            }
        } finally {
            WorldGfx.setDepthTest(false);
        }
    }

    private void drawTrail(Track tr, Entity e, float pt, double op) {
        int cap = tr.pts.size() + 1;
        double[] xs = new double[cap], ys = new double[cap], zs = new double[cap], ts = new double[cap];
        int n = 0;
        double len = Math.max(1, length.value);
        boolean live = e != null && tr.lastMoved == tick;
        for (Sample s : tr.pts) {
            double x = s.x(), y = s.y(), z = s.z();
            if (n == 0 && live) { Vec3 ip = e.getPosition(pt); x = ip.x; y = ip.y; z = ip.z; }   // smooth head between ticks
            if (n > 0) {
                double dx = x - xs[n - 1], dy = y - ys[n - 1], dz = z - zs[n - 1];
                if (dx * dx + dy * dy + dz * dz < 1.0e-6) continue;
            }
            xs[n] = x; ys[n] = y; zs[n] = z;
            ts[n] = Math.min(1.0, ((tick - s.tick()) + pt) / len);
            n++;
        }
        if (n < 2) return;
        band(xs, ys, zs, ts, n, width.value, op, tr.kind, true);
    }

    /** Predicted flight: a thinner ribbon that fades with distance, then a ring where it ends. */
    private void drawPath(Track tr, Entity e, float pt, double op) {
        List<Vec3> pts = tr.path.pts;
        int n = pts.size();
        double[] xs = new double[n], ys = new double[n], zs = new double[n], ts = new double[n];
        for (int i = 0; i < n; i++) {
            Vec3 p = pts.get(i);
            xs[i] = p.x; ys[i] = p.y; zs[i] = p.z;
            ts[i] = Math.min(0.95, i / (double) Math.max(8, n));       // fades slowly: the far end stays readable
        }
        if (e != null) { Vec3 ip = e.getPosition(pt); xs[0] = ip.x; ys[0] = ip.y; zs[0] = ip.z; }
        band(xs, ys, zs, ts, n, width.value * 0.55, op * 0.75, tr.kind, false);

        Vec3 end = tr.path.end;
        int rgb = rgbFor(tr.kind, 0);
        if (landing.value) {
            int ring = tr.path.hit != null ? Look.BAD : rgb;
            double y = end.y + 0.03;
            WorldGfx.ring(end.x, y, end.z, 0.28, 0.40, argb(ring, op), 24);
            WorldGfx.ring(end.x, y, end.z, 0.06, 0.12, argb(ring, op), 12);
            if (glow.value) WorldGfx.glowDot(end.x, end.y, end.z, argb(ring, 1.0));
        }
        if (hitBox.value && tr.path.hit != null) {
            LivingEntity h = tr.path.hit;
            AABB b = h.getBoundingBox();
            WorldGfx.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, argb(Look.BAD, 1.0), argb(Look.BAD, 0.16), 2, glow.value);
        }
    }

    /** Camera-facing ribbon along the points; alpha fades towards t = 1, with an optional soft halo. */
    private void band(double[] xs, double[] ys, double[] zs, double[] ts, int n, double w, double op, Kind kind, boolean taper) {
        Vec3 cam = Projector.camPos;
        double[] sx = new double[n], sy = new double[n], sz = new double[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            double dx = xs[b] - xs[a], dy = ys[b] - ys[a], dz = zs[b] - zs[a];
            double vx = xs[i] - cam.x, vy = ys[i] - cam.y, vz = zs[i] - cam.z;
            double cx = dy * vz - dz * vy, cy = dz * vx - dx * vz, cz = dx * vy - dy * vx;
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (cl < 1e-9) { sx[i] = 0; sy[i] = 1; sz[i] = 0; } else { sx[i] = cx / cl; sy[i] = cy / cl; sz[i] = cz / cl; }
        }
        if (glow.value) strip(xs, ys, zs, sx, sy, sz, ts, n, w * 2.6, op * 0.28, true, kind, taper);
        strip(xs, ys, zs, sx, sy, sz, ts, n, w, op, glow.value, kind, taper);
    }

    private void strip(double[] xs, double[] ys, double[] zs, double[] sx, double[] sy, double[] sz, double[] ts, int n,
                       double w, double op, boolean softEdges, Kind kind, boolean taper) {
        for (int i = 0; i < n - 1; i++) {
            double t0 = ts[i], t1 = ts[i + 1];
            double w0 = (taper ? w * Math.pow(1.0 - t0, 0.7) : w) * 0.5, w1 = (taper ? w * Math.pow(1.0 - t1, 0.7) : w) * 0.5;
            int c0 = rgbFor(kind, t0), c1 = rgbFor(kind, t1);
            double a0 = op * Math.pow(1.0 - t0, 1.2), a1 = op * Math.pow(1.0 - t1, 1.2);
            int m0 = argb(c0, a0), m1 = argb(c1, a1);
            int e0 = softEdges ? argb(c0, 0.0) : m0, e1 = softEdges ? argb(c1, 0.0) : m1;
            double lx0 = xs[i] - sx[i] * w0, ly0 = ys[i] - sy[i] * w0, lz0 = zs[i] - sz[i] * w0;
            double rx0 = xs[i] + sx[i] * w0, ry0 = ys[i] + sy[i] * w0, rz0 = zs[i] + sz[i] * w0;
            double lx1 = xs[i + 1] - sx[i + 1] * w1, ly1 = ys[i + 1] - sy[i + 1] * w1, lz1 = zs[i + 1] - sz[i + 1] * w1;
            double rx1 = xs[i + 1] + sx[i + 1] * w1, ry1 = ys[i + 1] + sy[i + 1] * w1, rz1 = zs[i + 1] + sz[i + 1] * w1;
            WorldGfx.gradientQuad(lx0, ly0, lz0, e0, xs[i], ys[i], zs[i], m0, xs[i + 1], ys[i + 1], zs[i + 1], m1, lx1, ly1, lz1, e1);
            WorldGfx.gradientQuad(xs[i], ys[i], zs[i], m0, rx0, ry0, rz0, e0, rx1, ry1, rz1, e1, xs[i + 1], ys[i + 1], zs[i + 1], m1);
        }
    }

    /** Distance and time-to-impact label at the landing point. */
    @Override
    public void onHud(GuiGraphics g) {
        if (!labels.value || !predict.value || mc.player == null) return;
        for (Track tr : tracks.values()) {
            if (tr.path == null || tr.path.end == null) continue;
            Vec3 end = tr.path.end;
            if (Projector.depth(end.x, end.y, end.z) < -1.0 || !Projector.project(end.x, end.y + 0.5, end.z, pt)) continue;
            if (pt[0] < -80 || pt[1] < -20 || pt[0] > Projector.width() + 80 || pt[1] > Projector.height() + 20) continue;
            double d = Math.sqrt(mc.player.distanceToSqr(end.x, end.y, end.z));
            String text = Math.round(d) + "m  " + String.format("%.1f", tr.path.ticks / 20.0) + "s"
                    + (tr.path.hit != null ? "  " + tr.path.hit.getName().getString() : "");
            Look.tag(g, mc.font, text, (int) pt[0], (int) pt[1] - 7, 0xFF000000 | rgbFor(tr.kind, 0));
        }
    }
}
