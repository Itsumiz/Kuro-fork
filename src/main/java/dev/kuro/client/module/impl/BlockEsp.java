package dev.kuro.client.module.impl;

import dev.kuro.client.gui.BlockPickerScreen;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BlockEsp extends Module {
    private static final int MAX_BLOCKS = 3000;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kuro-blocks.txt");

    private record Found(BlockPos pos, Block block) {}

    /** A group of touching blocks of the same kind (see "Merge clusters"). */
    private record Cluster(Block block, int x1, int y1, int z1, int x2, int y2, int z2, int count) {
        double cx() { return (x1 + x2 + 1) / 2.0; }
        double cy() { return (y1 + y2 + 1) / 2.0; }
        double cz() { return (z1 + z2 + 1) / 2.0; }
    }

    public final Set<Block> targets = new LinkedHashSet<>();
    /** Optional per-block colour (hue 0..1). Blocks not in here use the global "Color" setting. */
    public final Map<Block, Float> hues = new HashMap<>();
    private final Setting.Num range = add(new Setting.Num("Range (chunks)", 4, 1, 8, 1));
    private final Setting.Num maxDrawn = add(new Setting.Num("Max blocks", 150, 25, 1500, 25));
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 1, 1, 3, 1));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool fill = add(new Setting.Bool("Fill", true));
    private final Setting.Num fillOpacity = add(new Setting.Num("Fill opacity", 40, 10, 200, 10));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.5f));
    private final Setting.Bool merge = add(new Setting.Bool("Merge clusters", false));
    private final Setting.Bool tracers = add(new Setting.Bool("Tracers", false));
    private final Setting.Num tracerMax = add(new Setting.Num("Tracer count", 20, 1, 60, 1));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", false));
    private final Setting.Bool alert = add(new Setting.Bool("Alert sound", true));
    private final Setting.Num alertCount = add(new Setting.Num("Alert count", 5, 1, 300, 1));
    private final Setting.Bool countBehind = add(new Setting.Bool("Count behind you", true));
    private final Setting.Num alertVolume = add(new Setting.Num("Alert volume", 1.0, 0.1, 2.0, 0.1));
    private final Setting.Num alertCooldown = add(new Setting.Num("Alert cooldown (s)", 3, 0, 30, 1));
    private static final SoundEvent ALERT = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("kuro", "alert"));
    private boolean armed = true;       // re-arms once the count drops below the threshold
    private long lastAlert;
    private final double[] pt = new double[2];
    private volatile List<Found> found = List.of();
    private volatile List<Cluster> clusters = List.of();
    private boolean dirty = true;
    private int timer;

    public BlockEsp() {
        super("Block ESP", Category.BASE);
        add(new Setting.Button("Choose blocks...", () -> mc.setScreen(new BlockPickerScreen(mc.screen, this))));
        load();
    }

    @Override protected void onEnable() { dirty = true; }
    @Override protected void onDisable() { armed = true; found = List.of(); clusters = List.of(); queue.clear(); queuePos = 0; }

    /** Called by the block picker after the selection changed. */
    public void changed() { save(); dirty = true; }

    /** Called by the block picker after a per-block colour was changed (no rescan needed). */
    public void colorChanged() { save(); }

    // ---- scanning: spread over many ticks so it never causes a lag spike ----
    private static final int CHUNKS_PER_TICK = 6;
    private final List<long[]> queue = new ArrayList<>();   // chunks still to scan {x, z}
    private int queuePos;
    private List<Found> building = new ArrayList<>();

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;

        if (dirty) { queue.clear(); queuePos = 0; timer = 1000; dirty = false; }   // restart with new settings
        if (queuePos >= queue.size()) {
            // idle: begin a new pass every ~2 seconds
            if (++timer < 40) return;
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
        building = new ArrayList<>();
        if (targets.isEmpty()) { found = List.of(); clusters = List.of(); return; }
        ChunkPos cp = mc.player.chunkPosition();
        int rad = range.intValue();
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++) queue.add(new long[]{cp.x + dx, cp.z + dz});
        queue.sort((a, b) -> Long.compare(
                (a[0] - cp.x) * (a[0] - cp.x) + (a[1] - cp.z) * (a[1] - cp.z),
                (b[0] - cp.x) * (b[0] - cp.x) + (b[1] - cp.z) * (b[1] - cp.z)));   // nearest chunks first
    }

    private void finishPass() {
        Vec3 p = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        building.sort((a, b) -> Double.compare(dist2(a.pos(), p), dist2(b.pos(), p)));   // nearest first
        found = building;
        clusters = merge.value ? buildClusters(building, p) : List.of();
        building = new ArrayList<>();
    }

    private static double dist2(BlockPos b, Vec3 p) {
        double dx = b.getX() + 0.5 - p.x, dy = b.getY() + 0.5 - p.y, dz = b.getZ() + 0.5 - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Groups blocks that touch (including diagonally) and are the same kind into one cluster each. */
    private static List<Cluster> buildClusters(List<Found> list, Vec3 player) {
        Map<Long, Block> at = new HashMap<>();
        for (Found f : list) at.put(f.pos().asLong(), f.block());
        Set<Long> seen = new HashSet<>();
        List<Cluster> out = new ArrayList<>();
        for (Found f : list) {
            if (!seen.add(f.pos().asLong())) continue;
            Block b = f.block();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(f.pos());
            int x1 = f.pos().getX(), y1 = f.pos().getY(), z1 = f.pos().getZ(), x2 = x1, y2 = y1, z2 = z1, n = 0;
            while (!queue.isEmpty()) {
                BlockPos c = queue.poll();
                n++;
                x1 = Math.min(x1, c.getX()); x2 = Math.max(x2, c.getX());
                y1 = Math.min(y1, c.getY()); y2 = Math.max(y2, c.getY());
                z1 = Math.min(z1, c.getZ()); z2 = Math.max(z2, c.getZ());
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos q = c.offset(dx, dy, dz);
                            long key = q.asLong();
                            if (!seen.contains(key) && at.get(key) == b) { seen.add(key); queue.add(q); }
                        }
            }
            out.add(new Cluster(b, x1, y1, z1, x2, y2, z2, n));
        }
        out.sort((a, c) -> Double.compare(cd2(a, player), cd2(c, player)));
        return out;
    }

    private static double cd2(Cluster c, Vec3 p) {
        double dx = c.cx() - p.x, dy = c.cy() - p.y, dz = c.cz() - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void scanChunk(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.maybeHas(s -> targets.contains(s.getBlock()))) continue;
            int baseY = (chunk.getMinSectionY() + i) << 4;
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        Block b = sec.getBlockState(x, y, z).getBlock();
                        if (!targets.contains(b)) continue;
                        building.add(new Found(new BlockPos(baseX + x, baseY + y, baseZ + z), b));
                        if (building.size() >= MAX_BLOCKS) return;
                    }
        }
    }

    /** The colour (hue) used for a block: its own pick if set, otherwise the global one. */
    public float hueFor(Block b) { return hues.getOrDefault(b, color.hue); }

    /** ARGB colours for this frame, one entry per selected block: {line, fill}. */
    private Map<Block, int[]> colours() {
        Map<Block, int[]> colours = new HashMap<>();
        for (Block b : targets) {
            int rgb = Look.rgb(hueFor(b));
            colours.put(b, new int[]{0xFF000000 | rgb, fill.value ? (fillOpacity.intValue() << 24) | rgb : 0});
        }
        return colours;
    }

    /**
     * World pass: queue every box in real world coordinates. WorldGfx draws them all in one GPU call through the
     * game's own projection, so they stay glued to their blocks and cost almost nothing.
     */
    @Override
    public void onWorld() {
        List<Found> list = found;
        if (list.isEmpty()) return;
        Vec3 c = Projector.camPos;
        double maxD = range.value * 16 + 8, max2 = maxD * maxD;
        int t = thickness.intValue();
        Map<Block, int[]> colours = colours();
        int limit = maxDrawn.intValue(), drawn = 0;
        boolean glowOn = glow.value;
        List<Cluster> cl = clusters;
        if (merge.value && !cl.isEmpty()) {
            for (Cluster k : cl) {
                if (drawn >= limit) break;   // sorted nearest-first
                double dx = k.cx() - c.x, dy = k.cy() - c.y, dz = k.cz() - c.z;
                if (dx * dx + dy * dy + dz * dz > max2) continue;
                if (Projector.depth(k.cx(), k.cy(), k.cz()) < -1.0) continue;
                int[] col = colours.get(k.block());
                if (col == null) continue;
                drawn++;
                WorldGfx.box(k.x1(), k.y1(), k.z1(), k.x1() + 1, k.y1() + 1, k.z2() + 1, col[0], col[1], t, glowOn && drawn <= 40);
            }
        } else {
            for (Found f : list) {
                if (drawn >= limit) break;   // list is sorted nearest-first
                BlockPos p = f.pos();
                double dx = p.getX() + 0.5 - c.x, dy = p.getY() + 0.5 - c.y, dz = p.getZ() + 0.5 - c.z;
                if (dx * dx + dy * dy + dz * dz > max2) continue;
                if (Projector.depth(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) < -1.0) continue; // behind us
                int[] col = colours.get(f.block());
                if (col == null) continue;
                drawn++;
                WorldGfx.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, col[0], col[1], t, glowOn && drawn <= 40);
            }
        }
    }

    /** HUD pass: only the 2D extras (tracers, labels) and the sound alert. The boxes themselves are in onWorld(). */
    @Override
    public void onHud(GuiGraphics g) {
        List<Found> list = found;
        if (list.isEmpty()) { armed = true; return; }
        Vec3 c = Projector.camPos;
        double maxD = range.value * 16 + 8, max2 = maxD * maxD;

        Map<Block, int[]> colours = colours();

        int limit = maxDrawn.intValue(), drawn = 0, tracerLimit = tracerMax.intValue();
        List<Cluster> cl = clusters;
        if (merge.value && !cl.isEmpty()) {
            for (Cluster k : cl) {
                if (drawn >= limit) break;   // sorted nearest-first
                double dx = k.cx() - c.x, dy = k.cy() - c.y, dz = k.cz() - c.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > max2) continue;
                if (Projector.depth(k.cx(), k.cy(), k.cz()) < -1.0) continue;
                int[] col = colours.get(k.block());
                if (col == null) continue;
                drawn++;
                if (tracers.value && drawn <= tracerLimit) tracer(g, k.cx(), k.cy(), k.cz(), col[0]);
                if (k.count() > 1 || labels.value) {
                    String text = (k.count() > 1 ? "x" + k.count() : "") + (labels.value ? (k.count() > 1 ? "  " : "") + Math.round(Math.sqrt(d2)) + "m" : "");
                    label(g, text, k.cx(), k.y1() + 1.3, k.cz(), col[0]);
                }
            }
        } else {
            for (Found f : list) {
                if (drawn >= limit) break;   // list is sorted nearest-first
                BlockPos p = f.pos();
                double dx = p.getX() + 0.5 - c.x, dy = p.getY() + 0.5 - c.y, dz = p.getZ() + 0.5 - c.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > max2) continue;
                if (Projector.depth(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) < -1.0) continue; // behind us
                int[] col = colours.get(f.block());
                if (col == null) continue;
                drawn++;
                if (tracers.value && drawn <= tracerLimit) tracer(g, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, col[0]);
                if (labels.value && drawn <= 12) label(g, Math.round(Math.sqrt(d2)) + "m", p.getX() + 0.5, p.getY() + 1.3, p.getZ() + 0.5, col[0]);
            }
        }

        // sound alert: counts every selected block in range (optionally including ones behind you),
        // independent of the "Max blocks" drawing cap
        int counted = 0;
        for (Found f : list) {
            BlockPos p = f.pos();
            double dx = p.getX() + 0.5 - c.x, dy = p.getY() + 0.5 - c.y, dz = p.getZ() + 0.5 - c.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;   // list is sorted nearest-first, but a cheap check beats a break here
            if (!countBehind.value && Projector.depth(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) < -1.0) continue;
            counted++;
        }
        int need = alertCount.intValue();
        if (counted < need) armed = true;
        else if (alert.value && armed) {
            long now = System.currentTimeMillis();
            if (now - lastAlert >= alertCooldown.value * 1000) {
                mc.player.playSound(ALERT, (float) alertVolume.value, 1.0f);
                lastAlert = now;
                armed = false;
            }
        }
    }

    /** A line from the crosshair to a point in the world (still points the right way when it is off-screen). */
    private void tracer(GuiGraphics g, double x, double y, double z, int argb) {
        boolean visible = Projector.project(x, y, z, pt);
        if (!visible) Projector.projectOrPoint(x, y, z, pt);
        Gfx.line(g, Projector.width() / 2.0, Projector.height() / 2.0, pt[0], pt[1], 1, Look.withAlpha(argb, 0xB4));
        if (visible && pt[0] > 2 && pt[1] > 2 && pt[0] < Projector.width() - 2 && pt[1] < Projector.height() - 2) {
            int px = (int) pt[0], py = (int) pt[1];
            dev.kuro.client.util.Ui.corners(g, px - 3, py - 3, px + 4, py + 4, 3, Look.withAlpha(argb, 0xFF), true, true, true, true);     // end dot (round, shader)
        }
    }

    private void label(GuiGraphics g, String text, double x, double y, double z, int argb) {
        if (text.isEmpty() || !Projector.project(x, y, z, pt)) return;
        if (pt[0] < -80 || pt[1] < -20 || pt[0] > Projector.width() + 80 || pt[1] > Projector.height() + 20) return;
        Look.tag(g, mc.font, text, (int) pt[0], (int) pt[1] - 7, argb);
    }

    // ---- persistence of the chosen block list ----
    private void load() {
        try {
            if (Files.exists(FILE)) {
                for (String line : Files.readAllLines(FILE)) {
                    // "namespace:block" or "namespace:block <hue>"
                    String[] parts = line.trim().split("\\s+");
                    Identifier id = Identifier.tryParse(parts[0]);
                    if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
                    var opt = BuiltInRegistries.BLOCK.get(id); if (opt.isEmpty()) continue; Block b = opt.get().value();
                    targets.add(b);
                    if (parts.length > 1) {
                        try { hues.put(b, Mth.clamp(Float.parseFloat(parts[1]), 0f, 0.999f)); }
                        catch (NumberFormatException ignored) {}
                    }
                }
                return;
            }
        } catch (IOException ignored) {}
        targets.add(Blocks.DIAMOND_ORE);
        targets.add(Blocks.DEEPSLATE_DIAMOND_ORE);
    }

    private void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (Block b : targets) {
                String id = BuiltInRegistries.BLOCK.getKey(b).toString();
                Float h = hues.get(b);
                lines.add(h == null ? id : id + " " + h);
            }
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, lines);
        } catch (IOException ignored) {}
    }
}
