package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage ESP: outlines every container the server has sent you (chests, trapped chests, ender chests, barrels, shulker
 * boxes, hoppers, droppers / dispensers, furnaces) through walls, each type in its own colour. It reads block entities,
 * so it also finds containers buried inside walls or floors that you cannot see.
 */
public class StorageEsp extends Module {
    private enum Type {
        CHEST("Chest", 0.08f), TRAPPED("Trapped chest", 0.0f), ENDER("Ender chest", 0.78f), BARREL("Barrel", 0.13f),
        SHULKER("Shulker box", 0.88f), HOPPER("Hopper", 0.5f), DISPENSER("Dropper / dispenser", 0.58f), FURNACE("Furnace", 0.03f);
        final String label; final float hue;
        Type(String label, float hue) { this.label = label; this.hue = hue; }
    }

    private static final String[] COLORS = {"By type", "Single colour"};

    private final Setting.Num range = add(new Setting.Num("Range (chunks)", 6, 1, 12, 1));
    private final Setting.Bool chests = add(new Setting.Bool("Chests", true));
    private final Setting.Bool trapped = add(new Setting.Bool("Trapped chests", true));
    private final Setting.Bool ender = add(new Setting.Bool("Ender chests", true));
    private final Setting.Bool barrels = add(new Setting.Bool("Barrels", true));
    private final Setting.Bool shulkers = add(new Setting.Bool("Shulker boxes", true));
    private final Setting.Bool hoppers = add(new Setting.Bool("Hoppers", false));
    private final Setting.Bool dispensers = add(new Setting.Bool("Droppers / dispensers", false));
    private final Setting.Bool furnaces = add(new Setting.Bool("Furnaces", false));
    private final Setting.Num colorMode = add(new Setting.Num("Color mode", 0, 0, COLORS.length - 1, 1).labelled(COLORS));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.1f));
    private final Setting.Num maxShown = add(new Setting.Num("Max shown", 200, 10, 1000, 10));
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 1, 1, 3, 1));
    private final Setting.Bool glow = add(new Setting.Bool("Glow (close, lone ones only)", false));
    private final Setting.Bool fill = add(new Setting.Bool("Fill", true));
    private final Setting.Num fillOpacity = add(new Setting.Num("Fill opacity", 45, 10, 200, 10));
    private final Setting.Bool tracers = add(new Setting.Bool("Tracers", false));
    private final Setting.Num tracerMax = add(new Setting.Num("Tracer count", 10, 1, 60, 1));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", false));            // one per group, never overlapping
    private final Setting.Bool declutter = add(new Setting.Bool("Thin outlines in big groups", true));   // groups: no fill / glow, so they stay readable
    private final Setting.Bool counts = add(new Setting.Bool("Count summary", true));

    /** near = other containers within a few blocks (0 = on its own); cluster = index into clusters. */
    private record Found(BlockPos pos, Type type, int near, int cluster) {}
    /** A group of containers standing close together: drawn with thin outlines and ONE label. */
    private record Cluster(int count, double cx, double cy, double cz, Type main, double dist) {}

    private volatile List<Cluster> clusters = List.of();

    private volatile List<Found> found = List.of();
    private final int[] tally = new int[Type.values().length];
    private int timer = 1000;
    private final double[] pt = new double[2];

    public StorageEsp() { super("Storage ESP", Category.BASE); }

    @Override protected void onEnable() { timer = 1000; }
    @Override protected void onDisable() { found = List.of(); clusters = List.of(); }

    private Type classify(BlockEntity be) {
        String p = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).getPath();
        switch (p) {
            case "chest": return chests.value ? Type.CHEST : null;
            case "trapped_chest": return trapped.value ? Type.TRAPPED : null;
            case "ender_chest": return ender.value ? Type.ENDER : null;
            case "barrel": return barrels.value ? Type.BARREL : null;
            case "hopper": return hoppers.value ? Type.HOPPER : null;
            case "dropper", "dispenser": return dispensers.value ? Type.DISPENSER : null;
            case "furnace", "blast_furnace", "smoker": return furnaces.value ? Type.FURNACE : null;
            default: return shulkers.value && p.endsWith("shulker_box") ? Type.SHULKER : null;
        }
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) { found = List.of(); clusters = List.of(); return; }
        if (++timer < 10) return;                                   // block entities are cheap to list: refresh every half second
        timer = 0;
        List<Found> out = new ArrayList<>();
        java.util.Arrays.fill(tally, 0);
        ChunkPos cp = mc.player.chunkPosition();
        int rad = range.intValue();
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cp.x + dx, cp.z + dz, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    Type t = classify(be);
                    if (t == null) continue;
                    out.add(new Found(be.getBlockPos(), t, 0, 0));
                    tally[t.ordinal()]++;
                }
            }
        Vec3 p = mc.player.position();
        out.sort((a, b) -> Double.compare(d2(a.pos(), p), d2(b.pos(), p)));      // nearest first, so the box limit keeps the close ones
        group(out, p);
    }

    private static final int LINK = 3;   // containers this many blocks apart (or less) count as one group

    /** Finds each container's neighbours and joins close ones into groups (union-find over a small grid). */
    private void group(List<Found> in, Vec3 p) {
        int n = in.size();
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            BlockPos b = in.get(i).pos();
            grid.computeIfAbsent(cell(Math.floorDiv(b.getX(), LINK), Math.floorDiv(b.getY(), LINK), Math.floorDiv(b.getZ(), LINK)), k -> new ArrayList<>()).add(i);
        }
        int[] parent = new int[n], near = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            BlockPos a = in.get(i).pos();
            int cx = Math.floorDiv(a.getX(), LINK), cy = Math.floorDiv(a.getY(), LINK), cz = Math.floorDiv(a.getZ(), LINK);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                List<Integer> cell = grid.get(cell(cx + dx, cy + dy, cz + dz));
                if (cell == null) continue;
                for (int j : cell) {
                    if (j == i) continue;
                    BlockPos b = in.get(j).pos();
                    if (Math.abs(a.getX() - b.getX()) > LINK || Math.abs(a.getY() - b.getY()) > LINK || Math.abs(a.getZ() - b.getZ()) > LINK) continue;
                    near[i]++;
                    int ri = find(parent, i), rj = find(parent, j);
                    if (ri != rj) parent[ri] = rj;
                }
            }
        }
        Map<Integer, Integer> idOf = new HashMap<>();
        List<double[]> acc = new ArrayList<>();           // {count, sx, sy, sz, nearest dist2}
        List<int[]> types = new ArrayList<>();
        int[] cid = new int[n];
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            Integer id = idOf.get(r);
            if (id == null) { id = acc.size(); idOf.put(r, id); acc.add(new double[]{0, 0, 0, 0, Double.MAX_VALUE}); types.add(new int[Type.values().length]); }
            cid[i] = id;
            BlockPos b = in.get(i).pos();
            double[] a = acc.get(id);
            a[0]++; a[1] += b.getX() + 0.5; a[2] += b.getY() + 1.0; a[3] += b.getZ() + 0.5;
            a[4] = Math.min(a[4], d2(b, p));
            types.get(id)[in.get(i).type().ordinal()]++;
        }
        List<Cluster> cl = new ArrayList<>();
        for (int k = 0; k < acc.size(); k++) {
            double[] a = acc.get(k);
            int best = 0;
            for (int t = 1; t < types.get(k).length; t++) if (types.get(k)[t] > types.get(k)[best]) best = t;
            cl.add(new Cluster((int) a[0], a[1] / a[0], a[2] / a[0], a[3] / a[0], Type.values()[best], Math.sqrt(a[4])));
        }
        List<Found> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Found(in.get(i).pos(), in.get(i).type(), near[i], cid[i]));
        clusters = cl;
        found = out;
    }

    private static long cell(int x, int y, int z) { return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static double d2(BlockPos b, Vec3 p) {
        double dx = b.getX() + 0.5 - p.x, dy = b.getY() + 0.5 - p.y, dz = b.getZ() + 0.5 - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private int rgbFor(Type t) { return Look.rgb(colorMode.intValue() == 1 ? color.hue : t.hue); }

    /** Chests and ender chests are a little smaller than a full block, like their model. */
    private static double[] bounds(Found f) {
        BlockPos p = f.pos();
        boolean small = f.type() == Type.CHEST || f.type() == Type.TRAPPED || f.type() == Type.ENDER;
        double in = small ? 0.0625 : 0.0, top = small ? 0.875 : 1.0;
        return new double[]{p.getX() + in, p.getY(), p.getZ() + in, p.getX() + 1 - in, p.getY() + top, p.getZ() + 1 - in};
    }

    @Override
    public void onWorld() {
        List<Found> list = found;
        if (list.isEmpty() || mc.level == null) return;
        Vec3 c = Projector.camPos;
        double maxD = range.value * 16 + 8, max2 = maxD * maxD;
        int t = thickness.intValue(), limit = maxShown.intValue(), drawn = 0;
        for (Found f : list) {
            if (drawn >= limit) break;
            BlockPos p = f.pos();
            double dx = p.getX() + 0.5 - c.x, dy = p.getY() + 0.5 - c.y, dz = p.getZ() + 0.5 - c.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            if (Projector.depth(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) < -1.0) continue;
            int rgb = rgbFor(f.type());
            double[] b = bounds(f);
            drawn++;
            boolean crowded = declutter.value && f.near() >= 2;                       // part of a big group: thin outline only
            boolean lone = f.near() == 0;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean halo = glow.value && lone && dist < 24.0 && drawn <= 10;          // the halo is a fixed screen size, so on far boxes it spills past them
            int fillArgb = fill.value && !crowded ? (fillOpacity.intValue() << 24) | rgb : 0;
            WorldGfx.box(b[0], b[1], b[2], b[3], b[4], b[5], 0xFF000000 | rgb, fillArgb, crowded ? 1 : t, halo);
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        List<Found> list = found;
        if (list.isEmpty() || mc.level == null) return;
        if (counts.value) summary(g);
        if (!tracers.value && !labels.value) return;
        Vec3 c = Projector.camPos;
        double maxD = range.value * 16 + 8, max2 = maxD * maxD;
        List<Cluster> cl = clusters;
        // nearest group first, so the close ones win the label space
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cl.size(); i++) order.add(i);
        order.sort((x, y) -> Double.compare(cl.get(x).dist(), cl.get(y).dist()));
        List<int[]> placed = new ArrayList<>();
        int tl = tracerMax.intValue(), traced = 0, labelled = 0;
        for (int i : order) {
            Cluster k = cl.get(i);
            double dx = k.cx() - c.x, dy = k.cy() - c.y, dz = k.cz() - c.z;
            if (dx * dx + dy * dy + dz * dz > max2 || Projector.depth(k.cx(), k.cy(), k.cz()) < -1.0) continue;
            int argb = 0xFF000000 | rgbFor(k.main());
            if (tracers.value && traced < tl) {                                    // one line per group, to its middle
                traced++;
                if (!Projector.project(k.cx(), k.cy(), k.cz(), pt)) Projector.projectOrPoint(k.cx(), k.cy(), k.cz(), pt);
                Gfx.line(g, Projector.width() / 2.0, Projector.height() / 2.0, pt[0], pt[1], 1, Look.withAlpha(argb, 0x90));
            }
            if (!labels.value || labelled >= 12 || !Projector.project(k.cx(), k.cy() + 0.4, k.cz(), pt)) continue;
            if (pt[0] < -80 || pt[1] < -20 || pt[0] > Projector.width() + 80 || pt[1] > Projector.height() + 20) continue;
            String text = (k.count() > 1 ? k.count() + " containers" : k.main().label) + "  " + Math.round(k.dist()) + "m";
            int w = mc.font.width(text) + 16, x = (int) pt[0] - w / 2, y = (int) pt[1] - 18;
            boolean clash = false;
            for (int[] q : placed) if (x < q[2] + 3 && x + w > q[0] - 3 && y < q[3] + 3 && y + 16 > q[1] - 3) { clash = true; break; }
            if (clash) continue;                                                   // never stack labels on top of each other
            placed.add(new int[]{x, y, x + w, y + 16});
            labelled++;
            Look.tag(g, mc.font, text, (int) pt[0], (int) pt[1] - 7, argb);
        }
    }

    /** Small text under the top of the screen: how many of each kind are in range. */
    private void summary(GuiGraphics g) {
        StringBuilder sb = new StringBuilder();
        for (Type t : Type.values()) {
            int n = tally[t.ordinal()];
            if (n > 0) sb.append(sb.length() > 0 ? "   " : "").append(n).append(' ').append(t.label.toLowerCase());
        }
        if (sb.length() == 0) return;
        Look.tag(g, mc.font, sb.toString(), Projector.width() / 2, 30, 0xFFFFFFFF);
    }
}
