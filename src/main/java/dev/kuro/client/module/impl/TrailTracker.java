package dev.kuro.client.module.impl;

import dev.kuro.client.KuroClient;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Finds;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Follows trails of new chunks. A player flying or travelling through unexplored land leaves a long, thin line of
 * freshly generated chunks. This takes the New Chunks data (turn that module on), groups touching chunks, keeps the
 * long thin groups, fits a line through each and draws it with an arrow pointing the way the traveller went
 * (towards the newest chunks; when no times are known, towards the end furthest from you).
 */
public class TrailTracker extends Module {
    private record Trail(double ax, double az, double bx, double bz, int count, int chunksLong, boolean timed) {}   // a = tail, b = head (world coords)

    private final Setting.Num minChunks = add(new Setting.Num("Min chunks in trail", 8, 4, 60, 1));
    private final Setting.Num minLength = add(new Setting.Num("Min length (chunks)", 8, 4, 80, 1));
    private final Setting.Num shape = add(new Setting.Num("Thinness (length / width)", 2.5, 1.5, 8, 0.5));
    private final Setting.Num gap = add(new Setting.Num("Gap allowed (chunks)", 2, 1, 4, 1));
    private final Setting.Num range = add(new Setting.Num("Max range (chunks)", 80, 20, 300, 10));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.12f).wheel());
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 2, 1, 3, 1));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", true));
    private final Setting.Bool followHeight = add(new Setting.Bool("Draw at my height", true));
    private final Setting.Num maxTrails = add(new Setting.Num("Max trails", 6, 1, 20, 1));

    private volatile List<Trail> trails = List.of();
    private int timer = 1000;
    private final double[] a = new double[4];

    public TrailTracker() {
        super("Trail Tracker", Category.BASE);
        add(new Setting.Button("Baritone: trail head", this::gotoHead));
    }

    @Override protected void onEnable() { timer = 1000; }
    @Override protected void onDisable() { trails = List.of(); Finds.set("Trail", List.of()); }

    private void gotoHead() {
        if (mc.player == null) return;
        Trail best = null;
        double bd = Double.MAX_VALUE;
        for (Trail t : trails) {
            double d = Math.hypot(t.bx() - mc.player.getX(), t.bz() - mc.player.getZ());
            if (d < bd) { bd = d; best = t; }
        }
        if (best == null) { if (mc.player != null) dev.kuro.client.command.CommandManager.say("No trails found yet (is New Chunks on?)"); return; }
        for (Module m : KuroClient.modules.modules)
            if (m instanceof BaritoneControl b) b.cmd("goto " + (int) best.bx() + " " + (int) best.bz());
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (++timer < 60) return;           // analyse every ~3 seconds
        timer = 0;
        NewChunks nc = NewChunks.get();
        if (nc == null || !nc.isEnabled()) { trails = List.of(); Finds.set("Trail", List.of()); return; }

        ChunkPos pc = mc.player.chunkPosition();
        int rad = range.intValue();
        Set<Long> pool = new HashSet<>();
        for (long c : new ArrayList<>(nc.chunkSet())) {
            if (Math.abs(ChunkPos.getX(c) - pc.x) <= rad && Math.abs(ChunkPos.getZ(c) - pc.z) <= rad) pool.add(c);
            if (pool.size() >= 6000) break;
        }
        Map<Long, Long> times = new HashMap<>(nc.chunkTimes());

        List<Trail> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int g = gap.intValue();
        for (long start : pool) {
            if (!seen.add(start)) continue;
            List<Long> comp = new ArrayList<>();
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(start);
            while (!q.isEmpty()) {
                long c = q.poll();
                comp.add(c);
                int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
                for (int dx = -g; dx <= g; dx++)
                    for (int dz = -g; dz <= g; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        long k = ChunkPos.asLong(cx + dx, cz + dz);
                        if (pool.contains(k) && seen.add(k)) q.add(k);
                    }
            }
            if (comp.size() < minChunks.intValue()) continue;
            Trail t = fit(comp, times);
            if (t != null) out.add(t);
        }
        out.sort((x, y) -> Integer.compare(y.chunksLong(), x.chunksLong()));
        if (out.size() > maxTrails.intValue()) out = new ArrayList<>(out.subList(0, maxTrails.intValue()));
        trails = out;

        List<Finds.Find> pub = new ArrayList<>();
        for (Trail t : out)
            pub.add(new Finds.Find("Trail", "Trail head, " + t.chunksLong() + " chunks long" + (t.timed() ? "" : " (direction guessed)"),
                    (int) t.bx(), (int) mc.player.getY(), (int) t.bz(), t.chunksLong()));
        Finds.set("Trail", pub);
    }

    /** Fits a line through a group of chunks; null when the group is not long and thin enough to be a trail. */
    private Trail fit(List<Long> comp, Map<Long, Long> times) {
        int n = comp.size();
        double mx = 0, mz = 0;
        for (long c : comp) { mx += ChunkPos.getX(c); mz += ChunkPos.getZ(c); }
        mx /= n; mz /= n;
        double cxx = 0, cxz = 0, czz = 0;
        for (long c : comp) {
            double dx = ChunkPos.getX(c) - mx, dz = ChunkPos.getZ(c) - mz;
            cxx += dx * dx; cxz += dx * dz; czz += dz * dz;
        }
        double ang = 0.5 * Math.atan2(2 * cxz, cxx - czz), ux = Math.cos(ang), uz = Math.sin(ang);
        double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE, sMin = Double.MAX_VALUE, sMax = -Double.MAX_VALUE;
        double[] ts = new double[n];
        for (int i = 0; i < n; i++) {
            long c = comp.get(i);
            double dx = ChunkPos.getX(c) - mx, dz = ChunkPos.getZ(c) - mz;
            double t = dx * ux + dz * uz, s = -dx * uz + dz * ux;
            ts[i] = t;
            tMin = Math.min(tMin, t); tMax = Math.max(tMax, t);
            sMin = Math.min(sMin, s); sMax = Math.max(sMax, s);
        }
        double length = tMax - tMin + 1, width = sMax - sMin + 1;
        if (length < minLength.intValue() || length / Math.max(1.0, width) < shape.value) return null;

        // which end is the head? the end whose chunks were found most recently
        double span = tMax - tMin, lowSum = 0, highSum = 0;
        int lowN = 0, highN = 0;
        for (int i = 0; i < n; i++) {
            Long when = times.get(comp.get(i));
            if (when == null) continue;
            if (ts[i] <= tMin + span * 0.3) { lowSum += when; lowN++; }
            else if (ts[i] >= tMax - span * 0.3) { highSum += when; highN++; }
        }
        boolean timed = lowN > 0 && highN > 0 && Math.abs(lowSum / lowN - highSum / highN) > 5000;
        boolean headHigh;
        double ex1x = (mx + tMin * ux) * 16 + 8, ex1z = (mz + tMin * uz) * 16 + 8;      // low end, world coords
        double ex2x = (mx + tMax * ux) * 16 + 8, ex2z = (mz + tMax * uz) * 16 + 8;      // high end
        if (timed) headHigh = highSum / highN > lowSum / lowN;
        else {
            double d1 = Math.hypot(ex1x - mc.player.getX(), ex1z - mc.player.getZ());
            double d2 = Math.hypot(ex2x - mc.player.getX(), ex2z - mc.player.getZ());
            headHigh = d2 >= d1;
        }
        return headHigh ? new Trail(ex1x, ex1z, ex2x, ex2z, n, (int) Math.round(length), timed)
                        : new Trail(ex2x, ex2z, ex1x, ex1z, n, (int) Math.round(length), timed);
    }

    private void line(GuiGraphics g, double x1, double y, double z1, double x2, double z2, int t, int argb) {
        if (Projector.segment(x1, y, z1, x2, y, z2, a)) Gfx.line(g, a[0], a[1], a[2], a[3], t, argb);
    }

    @Override
    public void onHud(GuiGraphics g) {
        List<Trail> list = trails;
        if (list.isEmpty() || mc.player == null) return;
        int rgb = color.rgb(), line = Look.withAlpha(rgb, 0xE0), t = thickness.intValue();
        double y = followHeight.value ? Math.floor(mc.player.getY()) + 0.05 : mc.level.getMinY() + 0.05;
        double[] o = new double[2];
        for (Trail tr : list) {
            line(g, tr.ax(), y, tr.az(), tr.bx(), tr.bz(), t, line);
            // arrow head at the head end, pointing along the trail
            double dx = tr.bx() - tr.ax(), dz = tr.bz() - tr.az(), len = Math.hypot(dx, dz);
            if (len > 1) {
                dx /= len; dz /= len;
                double back = 18, side = 9;
                line(g, tr.bx(), y, tr.bz(), tr.bx() - dx * back - dz * side, tr.bz() - dz * back + dx * side, t, line);
                line(g, tr.bx(), y, tr.bz(), tr.bx() - dx * back + dz * side, tr.bz() - dz * back - dx * side, t, line);
            }
            if (labels.value && Projector.project(tr.bx(), y + 2, tr.bz(), o)) {
                double d = Math.hypot(tr.bx() - mc.player.getX(), tr.bz() - mc.player.getZ());
                Look.tagClamped(g, mc.font, "TRAIL HEAD  " + (int) d + "m  " + tr.chunksLong() + " chunks", (int) o[0], (int) o[1] - 7, 0xFF000000 | rgb);
            }
        }
    }
}
