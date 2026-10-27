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
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Light Debug (Base Finding): finds every block that gives off light (torches, lanterns, glowstone, froglights,
 * shroomlights, sea lanterns, candles, lamps, end rods ... anything with a light level above zero) in the chunks the
 * server has sent, and outlines it through walls. Light that is normal for the dimension you are in (lava, fire,
 * glow lichen, amethyst, redstone ore... and in the Nether glowstone / shroomlights too) is hidden by default, so what
 * is left is what somebody placed. "Ignore structures" also drops lights in and around chunks that look like a mineshaft,
 * village, stronghold, ocean monument, ancient city or trial chamber (judged from the blocks the server sent, so it is a best guess). Colour runs from blue (dim) to yellow (bright) unless you pick a single colour.
 */
public class LightDebug extends Module {
    private static final int CHUNKS_PER_TICK = 6;
    private static final int MAX_SCAN = 4000;      // safety cap on blocks kept per pass

    private final Setting.Num range = add(new Setting.Num("Range (chunks)", 6, 1, 12, 1));
    private final Setting.Num minLight = add(new Setting.Num("Min light level", 1, 1, 15, 1));
    private final Setting.Bool hideNatural = add(new Setting.Bool("Hide natural light", true));
    private final Setting.Bool ignoreStructures = add(new Setting.Bool("Ignore structures", true));
    private final Setting.Bool torches = add(new Setting.Bool("Torches / lanterns", true));
    private final Setting.Bool blocksOn = add(new Setting.Bool("Light blocks", true));
    private final Setting.Bool otherOn = add(new Setting.Bool("Everything else", true));
    private final Setting.Num maxDrawn = add(new Setting.Num("Max shown", 150, 10, 1000, 10));
    private final Setting.Bool byLevel = add(new Setting.Bool("Color by light level", true));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.14f));
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 1, 1, 3, 1));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool fill = add(new Setting.Bool("Fill", true));
    private final Setting.Num fillOpacity = add(new Setting.Num("Fill opacity", 50, 10, 200, 10));
    private final Setting.Bool tracers = add(new Setting.Bool("Tracers", false));
    private final Setting.Num tracerMax = add(new Setting.Num("Tracer count", 10, 1, 60, 1));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", true));
    private final Setting.Num labelMax = add(new Setting.Num("Label count", 12, 1, 60, 1));

    private record Found(BlockPos pos, Block block, int level) {}

    private final List<long[]> queue = new ArrayList<>();
    private int queuePos, timer = 1000;
    private boolean dirty = true;
    private List<Found> building = new ArrayList<>();
    private volatile List<Found> found = List.of();
    private net.minecraft.resources.ResourceKey<Level> lastWorld;
    private final double[] pt = new double[2];

    public LightDebug() { super("Light Debug", Category.BASE); }

    @Override protected void onEnable() { dirty = true; }
    @Override protected void onDisable() { found = List.of(); queue.clear(); queuePos = 0; chunkBits.clear(); }

    // ---- natural structures: their torches / lanterns / sea lanterns are not player bases ----
    private static final int B_WEB = 1, B_RAIL = 2, B_WOOD = 4, B_FENCE = 8, B_DWOOD = 16, B_DFENCE = 32,   // mineshaft clues (D = found below y 50)
            B_PRISM = 64, B_FRAME = 128, B_CITY = 256, B_TUFF = 512, B_COPPER = 1024, B_TRIAL = 2048, B_BELL = 4096, B_HAY = 8192, B_PATH = 16384;
    private static final Map<Block, Integer> MARK = new HashMap<>();
    static {
        for (Block b : BuiltInRegistries.BLOCK) {
            String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
            int bits = 0;
            if (p.equals("cobweb")) bits |= B_WEB;
            if (p.endsWith("rail")) bits |= B_RAIL;
            if (p.equals("oak_planks") || p.equals("dark_oak_planks")) bits |= B_WOOD;
            if (p.equals("oak_fence") || p.equals("dark_oak_fence")) bits |= B_FENCE;
            if (p.equals("prismarine_bricks") || p.equals("dark_prismarine")) bits |= B_PRISM;          // ocean monument
            if (p.equals("end_portal_frame")) bits |= B_FRAME;                                           // stronghold
            if (p.equals("reinforced_deepslate") || p.equals("sculk_shrieker")) bits |= B_CITY;          // ancient city
            if (p.contains("tuff_brick") || p.startsWith("polished_tuff") || p.startsWith("chiseled_tuff")) bits |= B_TUFF;
            if (p.contains("copper_bulb") || p.contains("copper_grate") || p.contains("copper_door")
                    || p.contains("copper_trapdoor") || p.contains("chiseled_copper")) bits |= B_COPPER;
            if (p.equals("trial_spawner") || p.equals("vault") || p.equals("heavy_core")) bits |= B_TRIAL;   // trial chamber
            if (p.equals("bell")) bits |= B_BELL;                                                        // village
            if (p.equals("hay_block")) bits |= B_HAY;
            if (p.equals("dirt_path")) bits |= B_PATH;
            if (bits != 0) MARK.put(b, bits);
        }
    }

    /** Chunk key -> structure clues found in it (kept between passes; the neighbours are checked in finishPass). */
    private final Map<Long, Integer> chunkBits = new HashMap<>();

    private static boolean isStructure(int m) {
        boolean web = (m & B_WEB) != 0, rail = (m & B_RAIL) != 0, wood = (m & B_WOOD) != 0, fence = (m & B_FENCE) != 0;
        boolean dw = (m & B_DWOOD) != 0, df = (m & B_DFENCE) != 0;
        boolean mine = (web && (rail || wood || fence)) || (rail && wood && fence) || (dw && df) || (rail && (dw || df));
        boolean trial = (m & B_TRIAL) != 0 || ((m & B_TUFF) != 0 && (m & B_COPPER) != 0);
        boolean village = (m & B_BELL) != 0 || ((m & B_HAY) != 0 && (m & B_PATH) != 0);
        return mine || trial || village || (m & (B_PRISM | B_FRAME | B_CITY)) != 0;
    }

    // ---- what counts as "normal" light for each dimension ----
    private static final Set<Block> NATURAL_ALL = new HashSet<>(List.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.MAGMA_BLOCK, Blocks.GLOW_LICHEN, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
            Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.SCULK_CATALYST, Blocks.CRYING_OBSIDIAN,
            Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.LIGHT));
    private static final Set<Block> NATURAL_NETHER = new HashSet<>(List.of(
            Blocks.GLOWSTONE, Blocks.SHROOMLIGHT, Blocks.SOUL_FIRE, Blocks.LAVA, Blocks.FIRE, Blocks.MAGMA_BLOCK));
    private static final Set<Block> NATURAL_END = new HashSet<>(List.of(Blocks.END_ROD));

    private boolean natural(Block b, Level lvl) {
        if (NATURAL_ALL.contains(b)) return true;
        if (lvl.dimension() == Level.NETHER) return NATURAL_NETHER.contains(b);
        if (lvl.dimension() == Level.END) return NATURAL_END.contains(b);
        return false;
    }

    /** Torches, lanterns, campfires, candles: the small hand-placed ones. */
    private static boolean isTorchy(Block b) {
        String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
        return p.contains("torch") || p.contains("lantern") || p.contains("campfire") || p.contains("candle") || p.equals("end_rod");
    }

    /** Full light-giving cubes: glowstone, froglights, shroomlights, sea lanterns, lamps, magma, jack o'lanterns... */
    private static boolean isBlocky(Block b) {
        String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
        return p.equals("glowstone") || p.equals("shroomlight") || p.equals("sea_lantern") || p.contains("froglight")
                || p.equals("redstone_lamp") || p.equals("jack_o_lantern") || p.equals("beacon") || p.equals("conduit")
                || p.equals("respawn_anchor") || p.contains("copper_bulb");
    }

    /** True when this block should be reported, given the "Torches / Light blocks / Everything else" switches. */
    private boolean wanted(Block b) {
        if (isTorchy(b)) return torches.value;
        if (isBlocky(b)) return blocksOn.value;
        return otherOn.value;
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (lastWorld != mc.level.dimension()) { lastWorld = mc.level.dimension(); found = List.of(); chunkBits.clear(); dirty = true; }
        if (dirty) { queue.clear(); queuePos = 0; timer = 1000; dirty = false; }
        if (queuePos >= queue.size()) {
            if (++timer < 30) return;                          // a new pass about every 1.5 s, spread over several ticks
            timer = 0;
            beginPass();
            if (queue.isEmpty()) return;
        }
        for (int n = 0; n < CHUNKS_PER_TICK && queuePos < queue.size(); n++) {
            long[] c = queue.get(queuePos++);
            LevelChunk chunk = mc.level.getChunkSource().getChunk((int) c[0], (int) c[1], false);
            if (chunk != null) scanChunk(chunk);
            if (building.size() >= MAX_SCAN) { queuePos = queue.size(); break; }
        }
        if (queuePos >= queue.size()) finishPass();
    }

    private void beginPass() {
        queue.clear();
        queuePos = 0;
        building = new ArrayList<>();
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
        if (ignoreStructures.value && !building.isEmpty()) {
            java.util.Set<Long> skip = new HashSet<>();              // a structure chunk and the ring around it (mineshafts spill over)
            for (Map.Entry<Long, Integer> e : chunkBits.entrySet()) {
                if (!isStructure(e.getValue())) continue;
                int cx = (int) (e.getKey() & 0xFFFFFFFFL), cz = (int) (e.getKey() >>> 32);   // ChunkPos.asLong layout
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) skip.add(ChunkPos.asLong(cx + dx, cz + dz));
            }
            building.removeIf(f -> skip.contains(ChunkPos.asLong(f.pos().getX() >> 4, f.pos().getZ() >> 4)));
        }
        building.sort((a, b) -> Double.compare(dist2(a.pos(), p), dist2(b.pos(), p)));   // nearest first
        found = building;
        building = new ArrayList<>();
    }

    private static double dist2(BlockPos b, Vec3 p) {
        double dx = b.getX() + 0.5 - p.x, dy = b.getY() + 0.5 - p.y, dz = b.getZ() + 0.5 - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void scanChunk(LevelChunk chunk) {
        int min = minLight.intValue();
        boolean hide = hideNatural.value, ign = ignoreStructures.value;
        Level lvl = mc.level;
        int bits = 0;
        try {
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection sec = sections[i];
                if (sec == null || sec.hasOnlyAir()) continue;
                if (!sec.maybeHas(s -> s.getLightEmission() >= min || (ign && MARK.containsKey(s.getBlock())))) continue;
                int baseY = (chunk.getMinSectionY() + i) << 4;
                int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
                for (int x = 0; x < 16; x++)
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++) {
                            BlockState st = sec.getBlockState(x, y, z);
                            Block b = st.getBlock();
                            if (ign) {
                                Integer mb = MARK.get(b);
                                if (mb != null) {
                                    bits |= mb;
                                    if (baseY + y < 50) {
                                        if ((mb & B_WOOD) != 0) bits |= B_DWOOD;
                                        if ((mb & B_FENCE) != 0) bits |= B_DFENCE;
                                    }
                                }
                            }
                            int level = st.getLightEmission();
                            if (level < min) continue;
                            if (hide && natural(b, lvl)) continue;
                            if (!wanted(b)) continue;
                            if (building.size() >= MAX_SCAN) return;
                            building.add(new Found(new BlockPos(baseX + x, baseY + y, baseZ + z), b, level));
                        }
            }
        } finally {
            long key = ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z);
            if (ign && bits != 0) chunkBits.put(key, bits); else chunkBits.remove(key);
        }
    }

    /** Blue (dim) -> cyan -> green -> yellow (bright). */
    private int rgbFor(int level) {
        if (!byLevel.value) return Look.rgb(color.hue);
        float t = Mth.clamp(level / 15f, 0f, 1f);
        return Look.rgb(Mth.lerp(t, 0.62f, 0.14f));
    }

    @Override
    public void onWorld() {
        List<Found> list = found;
        if (list.isEmpty()) return;
        Vec3 c = Projector.camPos;
        double maxD = range.value * 16 + 8, max2 = maxD * maxD;
        int t = thickness.intValue(), limit = maxDrawn.intValue(), drawn = 0;
        for (Found f : list) {
            if (drawn >= limit) break;   // sorted nearest-first
            BlockPos p = f.pos();
            double dx = p.getX() + 0.5 - c.x, dy = p.getY() + 0.5 - c.y, dz = p.getZ() + 0.5 - c.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            if (Projector.depth(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) < -1.0) continue;   // behind us
            int rgb = rgbFor(f.level());
            drawn++;
            WorldGfx.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                    0xFF000000 | rgb, fill.value ? (fillOpacity.intValue() << 24) | rgb : 0, t, glow.value && drawn <= 40);
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        List<Found> list = found;
        if (list.isEmpty() || (!tracers.value && !labels.value)) return;
        Vec3 c = Projector.camPos;
        double maxD = range.value * 16 + 8, max2 = maxD * maxD;
        int limit = maxDrawn.intValue(), drawn = 0, tracerLimit = tracerMax.intValue(), labelLimit = labelMax.intValue();
        for (Found f : list) {
            if (drawn >= limit) break;
            BlockPos p = f.pos();
            double dx = p.getX() + 0.5 - c.x, dy = p.getY() + 0.5 - c.y, dz = p.getZ() + 0.5 - c.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > max2) continue;
            if (Projector.depth(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) < -1.0) continue;
            drawn++;
            int argb = 0xFF000000 | rgbFor(f.level());
            if (tracers.value && drawn <= tracerLimit) tracer(g, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, argb);
            if (labels.value && drawn <= labelLimit)
                label(g, pretty(f.block()) + " L" + f.level() + "  " + Math.round(Math.sqrt(d2)) + "m", p.getX() + 0.5, p.getY() + 1.3, p.getZ() + 0.5, argb);
        }
    }

    private static String pretty(Block b) {
        String s = BuiltInRegistries.BLOCK.getKey(b).getPath().replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void tracer(GuiGraphics g, double x, double y, double z, int argb) {
        if (!Projector.project(x, y, z, pt)) Projector.projectOrPoint(x, y, z, pt);
        Gfx.line(g, Projector.width() / 2.0, Projector.height() / 2.0, pt[0], pt[1], 1, Look.withAlpha(argb, 0xB4));
    }

    private void label(GuiGraphics g, String text, double x, double y, double z, int argb) {
        if (!Projector.project(x, y, z, pt)) return;
        if (pt[0] < -80 || pt[1] < -20 || pt[0] > Projector.width() + 80 || pt[1] > Projector.height() + 20) return;
        Look.tag(g, mc.font, text, (int) pt[0], (int) pt[1] - 7, argb);
    }
}
