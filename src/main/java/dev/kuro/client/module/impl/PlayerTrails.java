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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Player Trails: a cosmetic trail behind players, purely client-side.
 *
 * Styles: Ribbon (soft glowing band), Comet (tapers to a point with a bright head), Helix (two strands twisting round the path),
 * Sparkles, Embers, Hearts, Stars, Snow (particles shed along the path) and Rings (ripples on the ground where you walk).
 * Colour mode "Theme" follows the UI theme (HUD > UI theme), so the trail matches the menus; Solid, Gradient and Rainbow are free choices.
 * Drawn with WorldGfx's depth-tested buffer, so trails are hidden by terrain like normal world objects (turn "Hide behind walls" off to see them through it).
 */
public class PlayerTrails extends Module {
    private static final String[] STYLES = {"Ribbon", "Comet", "Helix", "Sparkles", "Embers", "Hearts", "Stars", "Snow", "Rings"};
    private static final String[] COLORS = {"Theme", "Solid", "Gradient", "Rainbow"};
    private static final String[] ANCHORS = {"Feet", "Waist", "Head"};
    private static final int RIBBON = 0, COMET = 1, HELIX = 2, SPARKLE = 3, EMBER = 4, HEART = 5, STAR = 6, SNOW = 7, RING = 8;

    private final Setting.Num style = add(new Setting.Num("Style", 0, 0, STYLES.length - 1, 1).labelled(STYLES));
    private final Setting.Num colorMode = add(new Setting.Num("Color mode", 0, 0, COLORS.length - 1, 1).labelled(COLORS));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.58f).wheel());           // Solid, and the start of Gradient
    private final Setting.Hue color2 = add(new Setting.Hue("Color 2", 0.85f).wheel());        // end of Gradient
    private final Setting.Num colorSpeed = add(new Setting.Num("Rainbow speed", 1.0, 0.2, 4.0, 0.1));
    private final Setting.Num length = add(new Setting.Num("Length (ticks)", 26, 6, 100, 2));
    private final Setting.Num width = add(new Setting.Num("Width (blocks)", 0.32, 0.05, 1.2, 0.01));
    private final Setting.Num opacity = add(new Setting.Num("Opacity %", 85, 10, 100, 5));
    private final Setting.Num density = add(new Setting.Num("Particles per tick", 3, 1, 8, 1));
    private final Setting.Num anchor = add(new Setting.Num("Height", 0, 0, 2, 1).labelled(ANCHORS));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool movingOnly = add(new Setting.Bool("Only while moving", true));
    private final Setting.Bool depth = add(new Setting.Bool("Hide behind walls", true));
    private final Setting.Bool self = add(new Setting.Bool("Yourself", true));
    private final Setting.Bool firstPerson = add(new Setting.Bool("Show in first person", true));
    private final Setting.Bool others = add(new Setting.Bool("Other players", false));
    private final Setting.Bool friendColor = add(new Setting.Bool("Friends use friend colour", true));
    private final Setting.Num range = add(new Setting.Num("Range", 48, 8, 128, 4));

    private static final int MAX_PARTICLES = 1500;

    /** One recorded point of a ribbon: world position and the tick it was recorded. Newest first in Track.pts. */
    private record Sample(double x, double y, double z, long tick) {}

    private static final class Track {
        final ArrayDeque<Sample> pts = new ArrayDeque<>();
        double lx, ly, lz;
        boolean has;
        long lastTick = -1;
        double ringDist;
        int rgbOverride = -1;       // friend colour, -1 = none
    }

    private static final class Particle {
        double x, y, z, vx, vy, vz;
        int age, life, kind;
        float size, rot, spin, ct;   // ct = where on the colour gradient this particle sits (0..1)
        int rgbOverride = -1;
    }

    private final Map<UUID, Track> tracks = new HashMap<>();
    private final List<Particle> particles = new ArrayList<>();
    private final Random rng = new Random();
    private long tick;

    public PlayerTrails() { super("Player Trails", Category.RENDER); }

    @Override protected void onEnable() { clear(); }
    @Override protected void onDisable() { clear(); }

    private void clear() { tracks.clear(); particles.clear(); }

    // ------------------------------------------------------------------ simulation (once per tick)

    private boolean eligible(AbstractClientPlayer p) {
        if (p.isSpectator() || p.isInvisible() && p != mc.player) return false;
        if (p == mc.player) return self.value;
        return others.value && p.distanceToSqr(mc.player) <= range.value * range.value;
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) { clear(); return; }
        tick++;
        int st = style.intValue();
        int lifeTicks = length.intValue();
        Set<UUID> seen = new HashSet<>();

        for (AbstractClientPlayer p : mc.level.players()) {
            if (!eligible(p)) continue;
            UUID id = p.getUUID();
            seen.add(id);
            Track tr = tracks.computeIfAbsent(id, k -> new Track());
            tr.rgbOverride = friendColor.value && p != mc.player && Friends.is(p) ? (Friends.argb() & 0xFFFFFF) : -1;

            double x = p.getX(), y = p.getY(), z = p.getZ();
            double step = tr.has ? Math.sqrt((x - tr.lx) * (x - tr.lx) + (y - tr.ly) * (y - tr.ly) + (z - tr.lz) * (z - tr.lz)) : 0.0;
            if (step > 8.0) { tr.pts.clear(); step = 0.0; }          // teleported: don't draw a line across the map
            boolean moved = step >= 0.03;
            tr.lx = x; tr.ly = y; tr.lz = z; tr.has = true;
            double ay = y + anchorOffset(p);

            if (moved || !movingOnly.value) {
                if (st <= HELIX) {
                    tr.pts.addFirst(new Sample(x, ay, z, tick));
                    tr.lastTick = tick;
                } else if (st == RING) {
                    tr.ringDist += step;
                    if (tr.ringDist >= 0.9 && p.onGround()) { tr.ringDist = 0; spawnRing(tr, x, y + 0.02, z); }
                } else {
                    int n = density.intValue();
                    for (int i = 0; i < n; i++) spawn(st, tr, x, ay, z, p, moved);
                }
            }
            while (!tr.pts.isEmpty() && tick - tr.pts.peekLast().tick() > lifeTicks) tr.pts.removeLast();
        }
        tracks.keySet().retainAll(seen);

        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle q = it.next();
            if (++q.age > q.life) { it.remove(); continue; }
            q.x += q.vx; q.y += q.vy; q.z += q.vz;
            q.vx *= 0.96; q.vz *= 0.96;
            q.rot += q.spin;
            if (q.kind == SNOW) { q.vx += (rng.nextDouble() - 0.5) * 0.004; q.vz += (rng.nextDouble() - 0.5) * 0.004; }
            if (q.kind == HEART) q.vx += Math.sin((q.age + q.ct * 20.0) * 0.35) * 0.0035;
        }
    }

    private double anchorOffset(AbstractClientPlayer p) {
        return switch (anchor.intValue()) {
            case 1 -> p.getBbHeight() * 0.5;
            case 2 -> p.getBbHeight() * 0.92;
            default -> 0.08;
        };
    }

    private void spawnRing(Track tr, double x, double y, double z) {
        if (particles.size() >= MAX_PARTICLES) return;
        Particle q = new Particle();
        q.kind = RING; q.x = x; q.y = y; q.z = z; q.life = 18; q.size = 0.95f; q.ct = rng.nextFloat();
        q.rgbOverride = tr.rgbOverride;
        particles.add(q);
    }

    private void spawn(int st, Track tr, double x, double y, double z, AbstractClientPlayer p, boolean moved) {
        if (particles.size() >= MAX_PARTICLES) return;
        Particle q = new Particle();
        q.kind = st;
        double spread = st == SNOW ? 0.55 : 0.32;
        q.x = x + (rng.nextDouble() - 0.5) * spread;
        q.y = y + (rng.nextDouble() - 0.35) * (st == SNOW ? 0.9 : 0.5);
        q.z = z + (rng.nextDouble() - 0.5) * spread;
        q.vx = (rng.nextDouble() - 0.5) * 0.02;
        q.vz = (rng.nextDouble() - 0.5) * 0.02;
        q.ct = rng.nextFloat();
        q.rot = rng.nextFloat() * 6.2831f;
        q.rgbOverride = tr.rgbOverride;
        switch (st) {
            case SPARKLE -> { q.life = 14 + rng.nextInt(14); q.size = 0.07f + rng.nextFloat() * 0.09f; q.vy = (rng.nextDouble() - 0.4) * 0.02; q.spin = (rng.nextFloat() - 0.5f) * 0.3f; }
            case EMBER -> { q.life = 14 + rng.nextInt(18); q.size = 0.035f + rng.nextFloat() * 0.045f; q.vy = 0.018 + rng.nextDouble() * 0.03; q.spin = (rng.nextFloat() - 0.5f) * 0.4f; }
            case HEART -> { q.life = 26 + rng.nextInt(16); q.size = 0.10f + rng.nextFloat() * 0.07f; q.vy = 0.02 + rng.nextDouble() * 0.014; q.spin = 0f; q.rot = (rng.nextFloat() - 0.5f) * 0.5f; }
            case STAR -> { q.life = 20 + rng.nextInt(14); q.size = 0.09f + rng.nextFloat() * 0.08f; q.vy = -0.004 + rng.nextDouble() * 0.01; q.spin = (rng.nextFloat() - 0.5f) * 0.25f; }
            default -> { q.life = 28 + rng.nextInt(18); q.size = 0.04f + rng.nextFloat() * 0.04f; q.vy = -0.012 - rng.nextDouble() * 0.014; q.spin = 0f; }   // snow
        }
        particles.add(q);
    }

    // ------------------------------------------------------------------ colours

    /** 0xRRGGBB at position t along the trail (0 = head / newest, 1 = tail / oldest). */
    private int colorAt(double t, int override) {
        if (override >= 0) return override;
        t = Math.max(0.0, Math.min(1.0, t));
        switch (colorMode.intValue()) {
            case 0: return lerp(UiThemes.trailA(), UiThemes.trailB(), t);
            case 1: return color.rgb();
            case 2: return lerp(color.rgb(), color2.rgb(), t);
            default: {
                double h = (System.currentTimeMillis() / 1000.0 * 0.25 * colorSpeed.value - t * 0.6) % 1.0;
                if (h < 0) h += 1.0;
                return net.minecraft.util.Mth.hsvToRgb((float) h, 0.72f, 1f) & 0xFFFFFF;
            }
        }
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int argb(int rgb, double alpha) {
        int a = (int) Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ drawing (every frame, in the world)

    @Override
    public void onWorld() {
        if (!WorldGfx.active() || mc.level == null || mc.player == null) return;
        WorldGfx.setDepthTest(depth.value);
        try {
            float pt = Projector.tickProgress;
            int st = style.intValue();
            if (st <= HELIX) {
                for (AbstractClientPlayer p : mc.level.players()) {
                    Track tr = tracks.get(p.getUUID());
                    if (tr == null || tr.pts.isEmpty()) continue;
                    if (p == mc.player && !firstPerson.value && !mc.gameRenderer.getMainCamera().isDetached()) continue;
                    drawRibbon(p, tr, pt, st);
                }
            }
            drawParticles(pt);
        } finally {
            WorldGfx.setDepthTest(false);
        }
    }

    private void drawRibbon(AbstractClientPlayer p, Track tr, float pt, int st) {
        int cap = tr.pts.size() + 1;
        double[] xs = new double[cap], ys = new double[cap], zs = new double[cap], ts = new double[cap], bs = new double[cap];
        int n = 0;
        double len = Math.max(1, length.value);
        boolean live = tr.lastTick == tick;                          // newest sample is this tick's position: replace it with the smooth, interpolated one
        for (Sample s : tr.pts) {
            double x = s.x(), y = s.y(), z = s.z();
            if (n == 0 && live) {
                Vec3 ip = p.getPosition(pt);
                x = ip.x; y = ip.y + anchorOffset(p); z = ip.z;
            }
            if (n > 0) {
                double dx = x - xs[n - 1], dy = y - ys[n - 1], dz = z - zs[n - 1];
                if (dx * dx + dy * dy + dz * dz < 1.0e-6) continue;  // identical point (standing still): would give a zero-length segment
            }
            xs[n] = x; ys[n] = y; zs[n] = z;
            ts[n] = Math.min(1.0, ((tick - s.tick()) + pt) / len);
            bs[n] = s.tick();
            n++;
        }
        if (n < 2) return;
        double w = width.value;
        double op = opacity.value / 100.0;

        if (st == HELIX) {
            double[] hx = new double[n], hy = new double[n], hz = new double[n];
            for (int strand = 0; strand < 2; strand++) {
                for (int i = 0; i < n; i++) {
                    int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
                    double dx = xs[b] - xs[a], dz = zs[b] - zs[a];
                    double hl = Math.sqrt(dx * dx + dz * dz);
                    double sx = hl > 1e-6 ? -dz / hl : 1.0, sz = hl > 1e-6 ? dx / hl : 0.0;   // horizontal, sideways of travel
                    double ph = bs[i] * 0.55 + strand * Math.PI;                       // fixed in the world once recorded, so the twist trails behind like a real helix
                    double r = w * 0.9 * (1.0 - 0.5 * ts[i]);
                    hx[i] = xs[i] + sx * Math.cos(ph) * r;
                    hy[i] = ys[i] + Math.sin(ph) * r;
                    hz[i] = zs[i] + sz * Math.cos(ph) * r;
                }
                strip(hx, hy, hz, ts, n, w * 0.42, false, op, tr.rgbOverride);
            }
        } else {
            strip(xs, ys, zs, ts, n, w, st == COMET, op, tr.rgbOverride);
            if (st == COMET) {
                int rgb = colorAt(0, tr.rgbOverride);
                float hs = (float) (w * 0.9);
                WorldGfx.billboardPoly(xs[0], ys[0], zs[0], hs * 1.6, 0, DISC, argb(0xFFFFFF, op), argb(rgb, glow.value ? 0.0 : op * 0.9));
                if (glow.value) WorldGfx.billboardPoly(xs[0], ys[0], zs[0], hs * 3.2, 0, DISC, argb(rgb, op * 0.35), argb(rgb, 0.0));
            }
        }
    }

    /** Camera-facing band along the points. Alpha fades towards the tail; with Glow the edges fade out too and a wider halo sits underneath. */
    private void strip(double[] xs, double[] ys, double[] zs, double[] ts, int n, double w, boolean taper, double op, int override) {
        Vec3 cam = Projector.camPos;
        double[] sx = new double[n], sy = new double[n], sz = new double[n];
        for (int i = 0; i < n; i++) {                               // sideways direction: perpendicular to both the path and the line of sight
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            double dx = xs[b] - xs[a], dy = ys[b] - ys[a], dz = zs[b] - zs[a];
            double vx = xs[i] - cam.x, vy = ys[i] - cam.y, vz = zs[i] - cam.z;
            double cx = dy * vz - dz * vy, cy = dz * vx - dx * vz, cz = dx * vy - dy * vx;
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (cl < 1e-9) { sx[i] = 0; sy[i] = 1; sz[i] = 0; } else { sx[i] = cx / cl; sy[i] = cy / cl; sz[i] = cz / cl; }
        }
        boolean g = glow.value;
        if (g) band(xs, ys, zs, sx, sy, sz, ts, n, w * 2.6, taper, op * 0.28, true, override);
        band(xs, ys, zs, sx, sy, sz, ts, n, w, taper, op, g, override);
    }

    private void band(double[] xs, double[] ys, double[] zs, double[] sx, double[] sy, double[] sz, double[] ts, int n,
                      double w, boolean taper, double op, boolean softEdges, int override) {
        for (int i = 0; i < n - 1; i++) {
            double t0 = ts[i], t1 = ts[i + 1];
            double w0 = bandWidth(w, t0, taper) * 0.5, w1 = bandWidth(w, t1, taper) * 0.5;
            int c0 = colorAt(t0, override), c1 = colorAt(t1, override);
            double a0 = op * Math.pow(1.0 - t0, 1.3), a1 = op * Math.pow(1.0 - t1, 1.3);
            int m0 = argb(c0, a0), m1 = argb(c1, a1);
            int e0 = softEdges ? argb(c0, 0.0) : m0, e1 = softEdges ? argb(c1, 0.0) : m1;
            double lx0 = xs[i] - sx[i] * w0, ly0 = ys[i] - sy[i] * w0, lz0 = zs[i] - sz[i] * w0;
            double rx0 = xs[i] + sx[i] * w0, ry0 = ys[i] + sy[i] * w0, rz0 = zs[i] + sz[i] * w0;
            double lx1 = xs[i + 1] - sx[i + 1] * w1, ly1 = ys[i + 1] - sy[i + 1] * w1, lz1 = zs[i + 1] - sz[i + 1] * w1;
            double rx1 = xs[i + 1] + sx[i + 1] * w1, ry1 = ys[i + 1] + sy[i + 1] * w1, rz1 = zs[i + 1] + sz[i + 1] * w1;
            WorldGfx.gradientQuad(lx0, ly0, lz0, e0, xs[i], ys[i], zs[i], m0, xs[i + 1], ys[i + 1], zs[i + 1], m1, lx1, ly1, lz1, e1);   // left half
            WorldGfx.gradientQuad(xs[i], ys[i], zs[i], m0, rx0, ry0, rz0, e0, rx1, ry1, rz1, e1, xs[i + 1], ys[i + 1], zs[i + 1], m1);   // right half
        }
    }

    private static double bandWidth(double w, double t, boolean taper) {
        return taper ? w * Math.pow(1.0 - t, 0.7) : w * (1.0 - 0.4 * t);
    }

    private void drawParticles(float pt) {
        if (particles.isEmpty()) return;
        Vec3 cam = Projector.camPos;
        double max2 = (range.value + 16) * (range.value + 16);
        double op = opacity.value / 100.0;
        boolean g = glow.value;
        for (Particle q : particles) {
            double dx = q.x - cam.x, dy = q.y - cam.y, dz = q.z - cam.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            double u = Math.min(1.0, (q.age + pt) / q.life);                       // 0 = born, 1 = gone
            double fade = Math.min(1.0, u * 6.0) * (1.0 - u);
            double x = q.x + q.vx * pt, y = q.y + q.vy * pt, z = q.z + q.vz * pt;   // glide between ticks
            int rgb = colorAt(q.kind == EMBER ? u : q.ct, q.rgbOverride);
            switch (q.kind) {
                case SPARKLE -> {
                    double tw = 0.55 + 0.45 * Math.sin((q.age + pt) * 1.3 + q.ct * 9.0);
                    double s = q.size * (1.0 - 0.3 * u);
                    if (g) WorldGfx.billboardPoly(x, y, z, s * 2.4, 0, DISC, argb(rgb, op * 0.22 * fade * tw), argb(rgb, 0.0));
                    WorldGfx.billboardPoly(x, y, z, s * 1.5, q.rot + (q.age + pt) * q.spin, SPARK4, argb(0xFFFFFF, op * fade * tw), argb(lerp(rgb, 0xFFFFFF, 0.25), op * 0.85 * fade * tw));
                }
                case EMBER -> {
                    int hot = lerp(0xFFFFFF, rgb, Math.min(1.0, u * 2.0));
                    if (g) WorldGfx.billboardPoly(x, y, z, q.size * 3.2, 0, DISC, argb(rgb, op * 0.25 * fade), argb(rgb, 0.0));
                    WorldGfx.billboardPoly(x, y, z, q.size * (1.0 - 0.5 * u), q.rot, DIAMOND, argb(hot, op * fade), argb(rgb, op * 0.9 * fade));
                }
                case HEART -> {
                    double s = q.size * (0.7 + 0.5 * Math.min(1.0, u * 4.0));
                    if (g) WorldGfx.billboardPoly(x, y, z, s * 2.0, q.rot, HEART_POLY, argb(rgb, op * 0.2 * fade), argb(rgb, 0.0));
                    WorldGfx.billboardPoly(x, y, z, s, q.rot, HEART_POLY, argb(lerp(rgb, 0xFFFFFF, 0.35), op * fade), argb(rgb, op * 0.95 * fade));
                }
                case STAR -> {
                    double s = q.size * (1.0 - 0.25 * u);
                    if (g) WorldGfx.billboardPoly(x, y, z, s * 2.2, 0, DISC, argb(rgb, op * 0.2 * fade), argb(rgb, 0.0));
                    WorldGfx.billboardPoly(x, y, z, s, q.rot + (q.age + pt) * q.spin, STAR5, argb(lerp(rgb, 0xFFFFFF, 0.5), op * fade), argb(rgb, op * 0.95 * fade));
                }
                case SNOW -> WorldGfx.billboardPoly(x, y, z, q.size, 0, DISC, argb(0xFFFFFF, op * fade), argb(lerp(rgb, 0xFFFFFF, 0.6), op * 0.8 * fade));
                case RING -> {
                    double e = 1.0 - (1.0 - u) * (1.0 - u);
                    double r = 0.15 + q.size * e, wd = 0.04 + 0.03 * (1.0 - u);
                    double a = op * (1.0 - u);
                    if (g) WorldGfx.ring(q.x, q.y, q.z, Math.max(0, r - wd * 3.0), r + wd * 3.0, argb(rgb, a * 0.22), 28);
                    WorldGfx.ring(q.x, q.y, q.z, Math.max(0, r - wd), r + wd, argb(lerp(rgb, 0xFFFFFF, 0.45), a * 0.95), 28);
                }
                default -> {}
            }
        }
    }

    // ------------------------------------------------------------------ unit shapes for camera-facing particles (x, y pairs, about -1..1)

    private static final float[] DISC = circle(10);
    private static final float[] DIAMOND = {0f, 1f, 0.7f, 0f, 0f, -1f, -0.7f, 0f};
    private static final float[] SPARK4 = starPoly(4, 1f, 0.22f);       // four-point glint
    private static final float[] STAR5 = starPoly(5, 1f, 0.45f);
    private static final float[] HEART_POLY = heart(22);

    private static float[] circle(int n) {
        float[] p = new float[n * 2];
        for (int i = 0; i < n; i++) { double a = Math.PI * 2 * i / n; p[i * 2] = (float) Math.cos(a); p[i * 2 + 1] = (float) Math.sin(a); }
        return p;
    }

    private static float[] starPoly(int points, float outer, float inner) {
        float[] p = new float[points * 4];
        for (int i = 0; i < points * 2; i++) {
            double a = Math.PI * i / points + Math.PI / 2;
            float r = i % 2 == 0 ? outer : inner;
            p[i * 2] = (float) Math.cos(a) * r; p[i * 2 + 1] = (float) Math.sin(a) * r;
        }
        return p;
    }

    private static float[] heart(int n) {
        float[] p = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double t = Math.PI * 2 * i / n;
            p[i * 2] = (float) (16 * Math.pow(Math.sin(t), 3)) / 16f;
            p[i * 2 + 1] = (float) (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)) / 16f;
        }
        return p;
    }
}
