package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds amethyst geodes and outlines the whole thing in purple. A geode is every amethyst block / budding block /
 * crystal bud that touches (within 2 blocks) another one. Only geodes with at least N FULLY GROWN amethyst clusters
 * are shown (lots of grown clusters = long-lived player activity); everything else is ignored.
 * Uses the same outlined, glowing boxes as Block ESP.
 */
public class GeodeEsp extends Module {
    private static final int MAX_BLOCKS = 40000;
    private static final int CHUNKS_PER_TICK = 6;

    /** One detected geode: padded bounding box plus its grown clusters. */
    private record Geode(int x1, int y1, int z1, int x2, int y2, int z2, int grown, int buds, int budding, List<BlockPos> clusters) {
        double cx() { return (x1 + x2 + 1) / 2.0; }
        double cy() { return (y1 + y2 + 1) / 2.0; }
        double cz() { return (z1 + z2 + 1) / 2.0; }
    }

    private final Setting.Num range = add(new Setting.Num("Range (chunks)", 6, 1, 10, 1));
    private final Setting.Num minGrown = add(new Setting.Num("Min grown clusters", 8, 1, 60, 1));
    private final Setting.Bool wholeBox = add(new Setting.Bool("Box whole geode", true));
    private final Setting.Num padding = add(new Setting.Num("Shell padding", 2, 0, 4, 1));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.76f).wheel());
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 2, 1, 3, 1));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool fill = add(new Setting.Bool("Fill", true));
    private final Setting.Num fillOpacity = add(new Setting.Num("Fill opacity", 40, 10, 200, 10));
    private final Setting.Bool crystals = add(new Setting.Bool("Outline grown crystals", true));
    private final Setting.Bool tracers = add(new Setting.Bool("Tracers", true));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", true));
    private final Setting.Num maxGeodes = add(new Setting.Num("Max geodes", 10, 1, 40, 1));

    private final List<long[]> queue = new ArrayList<>();
    private int queuePos, timer = 1000;
    private boolean dirty = true;
    private Set<Long> building = new HashSet<>();
    private final List<BlockPos> buildClusters = new ArrayList<>();
    private final List<BlockPos> buildBuds = new ArrayList<>();
    private final List<BlockPos> buildBudding = new ArrayList<>();
    private volatile List<Geode> geodes = List.of();
    private final double[] pt = new double[2];

    public GeodeEsp() { super("Geode ESP", Category.BASE); }

    @Override protected void onEnable() { dirty = true; }
    @Override protected void onDisable() { dev.kuro.client.util.Finds.set("Geode", List.of()); geodes = List.of(); queue.clear(); queuePos = 0; }

    private static boolean isGeodeBlock(Block b) {
        return b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_CLUSTER
                || b == Blocks.LARGE_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.SMALL_AMETHYST_BUD;
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (dirty) { queue.clear(); queuePos = 0; timer = 1000; dirty = false; }
        if (queuePos >= queue.size()) {
            if (++timer < 40) return;       // new pass every ~2 s, spread over many ticks
            timer = 0;
            beginPass();
            if (queue.isEmpty()) return;
        }
        for (int n = 0; n < CHUNKS_PER_TICK && queuePos < queue.size(); n++) {
            long[] c = queue.get(queuePos++);
            LevelChunk chunk = mc.level.getChunkSource().getChunk((int) c[0], (int) c[1], false);
            if (chunk != null) scanChunk(chunk);
            if (building.size() >= MAX_BLOCKS) { queuePos = queue.size(); break; }
        }
        if (queuePos >= queue.size()) finishPass();
    }

    private void beginPass() {
        queue.clear(); queuePos = 0;
        building = new HashSet<>();
        buildClusters.clear(); buildBuds.clear(); buildBudding.clear();
        ChunkPos cp = mc.player.chunkPosition();
        int rad = range.intValue();
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++) queue.add(new long[]{cp.x + dx, cp.z + dz});
        queue.sort((a, b) -> Long.compare(
                (a[0] - cp.x) * (a[0] - cp.x) + (a[1] - cp.z) * (a[1] - cp.z),
                (b[0] - cp.x) * (b[0] - cp.x) + (b[1] - cp.z) * (b[1] - cp.z)));
    }

    private void scanChunk(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.maybeHas(s -> isGeodeBlock(s.getBlock()))) continue;
            int baseY = (chunk.getMinSectionY() + i) << 4;
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        Block b = sec.getBlockState(x, y, z).getBlock();
                        if (!isGeodeBlock(b)) continue;
                        BlockPos p = new BlockPos(baseX + x, baseY + y, baseZ + z);
                        building.add(p.asLong());
                        if (b == Blocks.AMETHYST_CLUSTER) buildClusters.add(p);
                        else if (b == Blocks.BUDDING_AMETHYST) buildBudding.add(p);
                        else if (b != Blocks.AMETHYST_BLOCK) buildBuds.add(p);
                    }
        }
    }

    /** Groups every touching geode block into one geode, then keeps the ones that qualify. */
    private void finishPass() {
        Set<Long> all = building;
        Set<Long> grown = new HashSet<>(), budding = new HashSet<>(), buds = new HashSet<>();
        for (BlockPos p : buildClusters) grown.add(p.asLong());
        for (BlockPos p : buildBudding) budding.add(p.asLong());
        for (BlockPos p : buildBuds) buds.add(p.asLong());

        Set<Long> seen = new HashSet<>();
        List<Geode> out = new ArrayList<>();
        int pad = padding.intValue();
        for (long start : all) {
            if (!seen.add(start)) continue;
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            BlockPos s0 = BlockPos.of(start);
            q.add(s0);
            int x1 = s0.getX(), y1 = s0.getY(), z1 = s0.getZ(), x2 = x1, y2 = y1, z2 = z1, n = 0, g = 0, bu = 0, bd = 0;
            List<BlockPos> cl = new ArrayList<>();
            while (!q.isEmpty()) {
                BlockPos c = q.poll();
                n++;
                long ck = c.asLong();
                if (grown.contains(ck)) { g++; cl.add(c); }
                else if (budding.contains(ck)) bd++;
                else if (buds.contains(ck)) bu++;
                x1 = Math.min(x1, c.getX()); x2 = Math.max(x2, c.getX());
                y1 = Math.min(y1, c.getY()); y2 = Math.max(y2, c.getY());
                z1 = Math.min(z1, c.getZ()); z2 = Math.max(z2, c.getZ());
                for (int dx = -2; dx <= 2; dx++)
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dz = -2; dz <= 2; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos nb = c.offset(dx, dy, dz);
                            long k = nb.asLong();
                            if (all.contains(k) && seen.add(k)) q.add(nb);
                        }
            }
            // fully grown clusters take a long time to grow, so a geode covered in them means a player has been
            // around for ages. Geodes with fewer grown clusters than the threshold are not shown at all.
            if (g < minGrown.intValue()) continue;
            out.add(new Geode(x1 - pad, y1 - pad, z1 - pad, x2 + pad, y2 + pad, z2 + pad, g, bu, bd, cl));
        }
        Vec3 p = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        out.sort((a, b) -> Double.compare(d2(a, p), d2(b, p)));
        geodes = out;
        List<dev.kuro.client.util.Finds.Find> pub = new ArrayList<>();
        for (Geode g : out)
            pub.add(new dev.kuro.client.util.Finds.Find("Geode", "Old geode, " + g.grown() + " grown clusters",
                    (int) g.cx(), (int) g.cy(), (int) g.cz(), g.grown()));
        dev.kuro.client.util.Finds.set("Geode", pub);
        building = new HashSet<>();
    }

    private static double d2(Geode g, Vec3 p) {
        double dx = g.cx() - p.x, dy = g.cy() - p.y, dz = g.cz() - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public void onWorld() {
        List<Geode> list = geodes;
        if (list.isEmpty()) return;
        int rgb = color.rgb(), line = 0xFF000000 | rgb, shade = fill.value ? (fillOpacity.intValue() << 24) | rgb : 0;
        int t = thickness.intValue(), drawn = 0, limit = maxGeodes.intValue();
        double maxD = range.value * 16 + 24, max2 = maxD * maxD;
        Vec3 c = Projector.camPos;
        for (Geode g : list) {
            if (drawn >= limit) break;
            double dx = g.cx() - c.x, dy = g.cy() - c.y, dz = g.cz() - c.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            drawn++;
            if (wholeBox.value) WorldGfx.box(g.x1(), g.y1(), g.z1(), g.x1() + 1, g.y1() + 1, g.z2() + 1, line, shade, t, glow.value);
            if (crystals.value) {
                for (BlockPos p : g.clusters())
                    WorldGfx.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, line, shade, t, glow.value && !wholeBox.value);
            }
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        List<Geode> list = geodes;
        if (list.isEmpty() || (!tracers.value && !labels.value)) return;
        int rgb = color.rgb(), line = 0xFF000000 | rgb;
        Vec3 c = Projector.camPos;
        int drawn = 0, limit = maxGeodes.intValue();
        double maxD = range.value * 16 + 24, max2 = maxD * maxD;
        for (Geode ge : list) {
            if (drawn >= limit) break;
            double dx = ge.cx() - c.x, dy = ge.cy() - c.y, dz = ge.cz() - c.z, d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > max2) continue;
            drawn++;
            if (tracers.value) {
                Projector.projectOrPoint(ge.cx(), ge.cy(), ge.cz(), pt);
                Gfx.line(g, Projector.width() / 2.0, Projector.height() / 2.0, pt[0], pt[1], 1, Look.withAlpha(line, 0xB4));
            }
            if (labels.value && Projector.project(ge.cx(), ge.y1() + 1.8, ge.cz(), pt)) {
                String text = "Old geode  " + Math.round(Math.sqrt(d2)) + "m  " + ge.grown() + " grown";
                Look.tag(g, mc.font, text, (int) pt[0], (int) pt[1] - 7, line);
            }
        }
    }
}
