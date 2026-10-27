package dev.kuro.client.module.impl;

import dev.kuro.client.util.SdfText;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.HudPanel;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Ui;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LevelChunk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rates every loaded chunk on how likely it is that a player base is in it and floats a
 * percentage tag above the worst ones.
 *
 * Each kind of evidence (machines, storage, farming, built blocks, torches underground, item frames
 * and mobs, old growth) turns into a 0..1 strength, and the strengths are combined like independent
 * clues (noisy-OR), so several different kinds of evidence push the percentage up much faster than
 * one kind alone. The neighbouring 8 chunks add a little, because bases cross chunk borders.
 * The percentage is a heuristic score, not a real probability.
 */
public class SusChunkFinder extends Module {
    private enum Sig {
        //        label           reliability  k   structure scale
        MACHINE("machines",       0.85,        3,  0.4),
        STORAGE("storage",        0.70,        4,  0.3),
        FARM   ("farming",        0.55,        8,  0.3),
        PLACED ("built blocks",   0.55,        14, 0.2),
        LIGHT  ("torches",        0.50,        8,  0.2),
        ENTITY ("frames/mobs",    0.55,        5,  0.5),
        GROWTH ("old growth",     0.20,        12, 1.0),
        REDSTONE("redstone",      0.75,        6,  0.4),
        PORTAL ("portals",        0.55,        6,  0.3),
        DECOR  ("crafted/decor",  0.50,        10, 0.3);

        final String label;
        final double reliability, k, structScale;
        Sig(String label, double reliability, double k, double structScale) {
            this.label = label; this.reliability = reliability; this.k = k; this.structScale = structScale;
        }
    }

    // marker bits found while scanning, used to spot natural structures (mineshafts, villages, ...)
    private static final int M_COBWEB = 1, M_RAIL = 2, M_STRUCT = 4, M_WOOD = 8, M_FENCE = 16,
            M_PLAYER = 32, M_SHIPWOOD = 64, M_TRIALCORE = 128, M_TUFF = 256, M_COPPERFX = 512,
            M_DWOOD = 1024, M_DFENCE = 2048;   // oak / dark oak planks / fences found below y 50 (mineshaft supports)   // M_PLAYER: machines/farms/torches/beds/doors/...; M_SHIPWOOD: planks, trapdoors, fences

    /**
     * Mineshaft evidence inside one chunk: cobwebs together with rails or oak/dark-oak wood, or the
     * rails + fence + planks combination. Most chunks of a mineshaft only hold some of these, so the
     * result is also spread to the neighbouring chunks (see mineChunks).
     */
    /** Trail chamber: a trial spawner / vault / heavy core, or tuff bricks together with copper bulbs / grates / doors. */
    private static boolean isTrial(int m) {
        return (m & M_TRIALCORE) != 0 || ((m & M_TUFF) != 0 && (m & M_COPPERFX) != 0);
    }

    private static boolean isMine(int m) {
        boolean web = (m & M_COBWEB) != 0, rail = (m & M_RAIL) != 0;
        boolean wood = (m & M_WOOD) != 0, fence = (m & M_FENCE) != 0;
        boolean deepWood = (m & M_DWOOD) != 0, deepFence = (m & M_DFENCE) != 0;
        return (web && (rail || wood || fence)) || (rail && wood && fence)
                // corridor chunks often hold only the supports (planks + fences, maybe a rail or a torch), no cobweb
                || (deepWood && deepFence) || (rail && (deepWood || deepFence));
    }

    private static final Set<String> MACHINE_IDS = Set.of("hopper", "dropper", "dispenser", "observer", "piston",
            "sticky_piston", "repeater", "comparator", "tnt", "daylight_detector", "beacon", "enchanting_table",
            "redstone_lamp", "note_block", "jukebox", "crafter");
    private static final Set<String> REDSTONE_IDS = Set.of("redstone_wire", "lever", "tripwire_hook", "target",
            "slime_block", "honey_block", "lightning_rod", "sculk_sensor", "calibrated_sculk_sensor", "redstone_torch",
            "redstone_wall_torch", "powered_rail", "activator_rail");
    private static final Set<String> DECOR_IDS = Set.of("flower_pot", "bookshelf", "chiseled_bookshelf", "lectern",
            "brewing_stand", "cauldron", "water_cauldron", "lava_cauldron", "grindstone", "lodestone", "respawn_anchor",
            "conduit", "bricks", "smooth_stone", "quartz_block", "chiseled_quartz_block", "quartz_pillar",
            "smooth_quartz", "polished_andesite", "polished_diorite", "polished_granite", "decorated_pot",
            "beehive", "jack_o_lantern", "carved_pumpkin", "cake", "end_crystal");
    private static final Set<String> FARM_IDS = Set.of("farmland", "wheat", "carrots", "potatoes", "beetroots",
            "nether_wart", "composter");
    private static final Set<String> PLACED_IDS = Set.of("crafting_table", "furnace", "blast_furnace", "smoker",
            "smithing_table", "fletching_table", "cartography_table", "loom", "stonecutter", "anvil", "chipped_anvil",
            "damaged_anvil", "ladder", "scaffolding", "glass", "tinted_glass", "glass_pane", "obsidian", "iron_block",
            "gold_block", "diamond_block", "emerald_block", "netherite_block", "lapis_block", "redstone_block");
    private static final Set<String> STRUCT_IDS = Set.of("end_portal_frame", "bell", "reinforced_deepslate",
            "sculk_shrieker", "trial_spawner", "vault", "nether_brick_fence", "gilded_blackstone", "purpur_block",
            "prismarine_bricks", "chiseled_sandstone", "crying_obsidian");

    private static final SoundEvent ALERT = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("kuro", "alert"));

    // ---- settings ----
    private final Setting.Num range = add(new Setting.Num("Range (chunks)", 6, 2, 12, 1));
    // how fast it searches: raise both when flying (elytra) and it cannot keep up; very high speed can cost FPS
    private final Setting.Num scanSpeed = add(new Setting.Num("Scan speed (chunks/tick)", 3, 1, 40, 1));
    private final Setting.Num passDelay = add(new Setting.Num("Rescan delay (ticks)", 40, 0, 40, 5));
    private final Setting.Bool skipScanned = add(new Setting.Bool("Skip scanned chunks", true));   // each loaded chunk is scanned once, not every pass
    private final Setting.Button rescanAll = add(new Setting.Button("Rescan all chunks", () -> { this.scanned.clear(); this.timer = 1000; }));
    private final Setting.Bool progress = add(new Setting.Bool("Scan progress", true));   // on-screen readout: chunks scanned this pass, unloaded chunks, last pass time
    private final Setting.Num minPercent = add(new Setting.Num("Min % to highlight", 30, 10, 90, 5));
    private final Setting.Num maxHighlights = add(new Setting.Num("Max highlights", 60, 5, 200, 5));
    private final Setting.Num maxDistance = add(new Setting.Num("Max distance", 800, 100, 3000, 100));
    private final Setting.Bool findWater = add(new Setting.Bool("Find in water", true));   // on = ocean / beach chunks are never written off as shipwrecks, so underwater stashes get flagged
    private final Setting.Bool flatFloor = add(new Setting.Bool("Flat at world bottom", true));
    private final Setting.Bool mergeChunks = add(new Setting.Bool("Merge nearby chunks", true));   // touching suspect chunks become one outline with one combined info callout
    private final Setting.Num farGrowth = add(new Setting.Num("Bigger when far %", 50, 0, 200, 10));   // squares grow with distance so far chunks stay readable (0 = true size)
    private final Setting.Bool atBase = add(new Setting.Bool("Marker at base height", true));   // draw at the clues' real height instead of the flat plane
    private final Setting.Bool indicator = add(new Setting.Bool("Corner indicator", true));
    private final Setting.Num baseAt = add(new Setting.Num("Base indicator at %", 50, 20, 95, 5));
    private final Setting.Bool hoverInfo = add(new Setting.Bool("Hover info", true));
    private final Setting.Bool chatAlert = add(new Setting.Bool("Chat alert", true));
    private final Setting.Bool soundAlert = add(new Setting.Bool("Alert sound", false));
    private final Setting.Num alertAt = add(new Setting.Num("Alert at %", 60, 20, 95, 5));
    private final Setting.Bool discordAlert = add(new Setting.Bool("Discord alert", true));
    private final Setting.Bool discordPlayers = add(new Setting.Bool("Discord: live players", false));
    private final Setting.Text discordHook = add(new Setting.Text("Discord webhook URL (paste + Enter)", url -> saveWebhook(url)));
    private final Setting.Text botToken = add(new Setting.Text("Discord bot token (paste + Enter)", t -> saveBot(t, null)));
    private final Setting.Text botUser = add(new Setting.Text("Discord your user ID (paste + Enter)", u -> saveBot(null, u)));
    { discordHook.maxLen = 300; botToken.maxLen = 200; botUser.maxLen = 30; }
    private final Setting.Button discordTest = add(new Setting.Button("Send Discord test", () -> sendDiscord("Kuro Sus Chunk Finder: test message. Webhook works.", true)));
    private final Setting.Bool flagPlayers = add(new Setting.Bool("Flag live players", true));
    private final Setting.Bool ignoreFriends = add(new Setting.Bool("Ignore friends", true));
    private final Setting.Bool playerChat = add(new Setting.Bool("Player chat alert", true));
    private final Setting.Bool playerSound = add(new Setting.Bool("Player alert sound", true));
    private final Setting.Num playerKeep = add(new Setting.Num("Player flag time (s)", 120, 10, 600, 10));
    private final Setting.Bool ignoreStructures = add(new Setting.Bool("Ignore structures", true));
    private final Setting.Bool regionMap = add(new Setting.Bool("Region map", true));
    private final Setting.Num mapRadius = add(new Setting.Num("Map radius (chunks)", 12, 4, 24, 2));
    private final Setting.Num mapCell = add(new Setting.Num("Map cell size", 4, 2, 8, 1));
    private final Setting.Bool tracerBest = add(new Setting.Bool("Tracer to best", false));
    private final Setting.Bool machines = add(new Setting.Bool("Sig: machines", true));
    private final Setting.Bool storage = add(new Setting.Bool("Sig: storage", true));
    private final Setting.Bool farming = add(new Setting.Bool("Sig: farming", true));
    private final Setting.Bool placed = add(new Setting.Bool("Sig: built blocks", true));
    private final Setting.Bool light = add(new Setting.Bool("Sig: torches", true));
    private final Setting.Bool entities = add(new Setting.Bool("Sig: frames/mobs", true));
    private final Setting.Bool growth = add(new Setting.Bool("Sig: old growth", true));
    private final Setting.Bool redstone = add(new Setting.Bool("Sig: redstone", true));
    private final Setting.Bool portals = add(new Setting.Bool("Sig: portals", true));
    private final Setting.Bool decor = add(new Setting.Bool("Sig: crafted/decor", true));
    private final Setting.Bool pets = add(new Setting.Bool("Sig: pets/leashed", true));
    // how strongly each check pushes the percentage (100 = default, 0 = no effect, 200 = double)
    private final Setting.Num wMachines = add(new Setting.Num("Weight: machines %", 100, 0, 200, 10));
    private final Setting.Num wStorage = add(new Setting.Num("Weight: storage %", 100, 0, 200, 10));
    private final Setting.Num wFarming = add(new Setting.Num("Weight: farming %", 100, 0, 200, 10));
    private final Setting.Num wPlaced = add(new Setting.Num("Weight: built blocks %", 100, 0, 200, 10));
    private final Setting.Num wLight = add(new Setting.Num("Weight: torches %", 100, 0, 200, 10));
    private final Setting.Num wEntities = add(new Setting.Num("Weight: frames/mobs %", 100, 0, 200, 10));
    private final Setting.Num wGrowth = add(new Setting.Num("Weight: old growth %", 100, 0, 200, 10));
    private final Setting.Num wRedstone = add(new Setting.Num("Weight: redstone %", 100, 0, 200, 10));
    private final Setting.Num wPortals = add(new Setting.Num("Weight: portals %", 100, 0, 200, 10));
    private final Setting.Num wDecor = add(new Setting.Num("Weight: crafted/decor %", 100, 0, 200, 10));

    private static final int LIVE = 0xFF38D9FF;            // colour of flagged (live player) chunks
    private static final double LIVE_RELIABILITY = 0.60;   // a player standing in a chunk counts as this strong a clue
    private static final int FILL_NEAREST = 24;            // nearest chunks that get a translucent fill (cheap now: it is all one GPU batch)

    // ---- data ----
    /** A player seen standing in a chunk; the chunk stays flagged for "Player flag time" after the last sighting. */
    private static final class Spot {
        String name;
        double x, y, z;
        long last;
        Spot(String name, double x, double y, double z, long last) { this.name = name; this.x = x; this.y = y; this.z = z; this.last = last; }
    }

    private static final class Info {
        final int cx, cz;
        final double[] counts = new double[Sig.values().length];
        int markers;
        double hx, hy, hz, hw;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double own, pct;
        String reasons = "";
        final List<String[]> rows = new ArrayList<>();   // tooltip lines: {points, text}
        int signals;                                      // how many different kinds of evidence
        Spot live;                                        // non-null while a player is flagged in this chunk
        boolean structure, mine, shipRaw, ship, trial;

        Info(int cx, int cz) { this.cx = cx; this.cz = cz; }

        void add(Sig s, double n, double x, double y, double z) {
            counts[s.ordinal()] += n;
            double base = switch (s) {
                case MACHINE, STORAGE, REDSTONE -> 3.0;
                case PORTAL -> 0.5;
                case FARM -> 2.0;
                case GROWTH -> 0.2;
                default -> 1.0;
            };
            double w = base * n;
            hx += x * w; hy += y * w; hz += z * w; hw += w;
            if (s != Sig.GROWTH) { minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        }

        double midX() { return hw > 0 ? hx / hw : cx * 16 + 8; }
        double midZ() { return hw > 0 ? hz / hw : cz * 16 + 8; }
        double midY(double fallback) { return hw > 0 ? hy / hw : fallback; }
        long key() { return ChunkPos.asLong(cx, cz); }

        /** Highlight volume: the whole chunk footprint, from just under the lowest clue to just over the highest. */
        void box(double[] out, double fallbackY) {
            double lo = minY <= maxY ? minY : fallbackY - 2, hi = minY <= maxY ? maxY : fallbackY + 3;
            lo -= 1.0; hi += 2.0;
            if (hi - lo < 6) { double m = (lo + hi) / 2; lo = m - 3; hi = m + 3; }
            out[0] = cx * 16.0; out[1] = lo; out[2] = cz * 16.0;
            out[3] = cx * 16.0 + 16; out[4] = hi; out[5] = cz * 16.0 + 16;
        }
    }

    private final Map<Block, Sig> classes = new HashMap<>();
    private final Map<Block, Integer> markers = new HashMap<>();

    private final Map<Long, Info> infos = new HashMap<>();      // remembered chunks (>= 12%)
    private Map<Long, Info> fresh = new HashMap<>();             // chunks scanned in the current pass
    private volatile List<Info> display = List.of();
    private final Set<Long> alerted = new HashSet<>();
    private final Set<Long> mineChunks = new HashSet<>();        // chunks that show mineshaft evidence
    private final Set<Long> trialChunks = new HashSet<>();       // chunks that show trail chamber evidence
    private final List<long[]> queue = new ArrayList<>();
    /** Chunks already scanned: chunk key -> identity of the loaded WorldChunk we scanned. A chunk is only scanned again
     *  if it was unloaded and loaded anew (a different object), or after "Rescan all chunks". */
    private final Map<Long, Integer> scanned = new HashMap<>();
    private int queuePos, timer;
    private Level lastWorld;
    private final double[] o = new double[2];
    private final double[] bx = new double[6];
    private final Map<Long, Spot> liveSpots = new HashMap<>();   // chunk -> player seen there
    private final Map<UUID, Long> seenAt = new HashMap<>();      // player -> last time seen (for "new player" alerts)

    // the three on-screen readouts can be dragged in the HUD layout editor
    private final HudPanel pProgress = new HudPanel(this, "Sus scan");
    private final HudPanel pMap = new HudPanel(this, "Sus region map");
    private final HudPanel pIndicator = new HudPanel(this, "Sus indicator");

    @Override
    public List<HudElement> hudElements() {
        List<HudElement> l = new java.util.ArrayList<>();
        if (progress.value) l.add(pProgress);
        if (regionMap.value) l.add(pMap);
        if (indicator.value) l.add(pIndicator);
        return l;
    }

    public SusChunkFinder() {
        super("Sus Chunk Finder", Category.BASE);
        for (Block b : BuiltInRegistries.BLOCK) {
            String p = BuiltInRegistries.BLOCK.getKey(b).getPath();
            Sig s = classify(p);
            if (s != null) classes.put(b, s);
            int bits = 0;
            if (s == Sig.MACHINE || s == Sig.FARM || s == Sig.LIGHT || s == Sig.REDSTONE || s == Sig.PORTAL) bits |= M_PLAYER;
            if (s == Sig.PLACED) bits |= (p.endsWith("_planks") || p.endsWith("_trapdoor") || p.endsWith("_fence")) ? M_SHIPWOOD : M_PLAYER;
            if (p.equals("cobweb")) bits |= M_COBWEB;
            if (p.endsWith("rail")) bits |= M_RAIL;
            if (STRUCT_IDS.contains(p)) bits |= M_STRUCT;
            if (p.equals("trial_spawner") || p.equals("vault") || p.equals("heavy_core")) bits |= M_TRIALCORE;
            if (p.contains("tuff_brick") || p.startsWith("polished_tuff") || p.startsWith("chiseled_tuff")) bits |= M_TUFF;
            if (p.contains("copper_bulb") || p.contains("copper_grate") || p.contains("copper_door")
                    || p.contains("copper_trapdoor") || p.contains("chiseled_copper")) bits |= M_COPPERFX;
            if (p.equals("oak_planks") || p.equals("dark_oak_planks")) bits |= M_WOOD;
            if (p.equals("oak_fence") || p.equals("dark_oak_fence")) bits |= M_FENCE;
            if (bits != 0) markers.put(b, bits);
        }
        classes.put(Blocks.AMETHYST_CLUSTER, Sig.GROWTH);
    }

    private static Sig classify(String p) {
        if (MACHINE_IDS.contains(p)) return Sig.MACHINE;
        if (REDSTONE_IDS.contains(p) || p.endsWith("_button") || p.endsWith("_pressure_plate")) return Sig.REDSTONE;
        if (p.equals("nether_portal")) return Sig.PORTAL;
        if (DECOR_IDS.contains(p) || p.startsWith("stripped_") || p.endsWith("_candle") || p.equals("candle")
                || p.endsWith("_skull") || p.endsWith("_head") || p.endsWith("_carpet")) return Sig.DECOR;
        if (FARM_IDS.contains(p)) return Sig.FARM;
        if (PLACED_IDS.contains(p) || p.endsWith("planks") || p.endsWith("_bed") || p.endsWith("_wool")
                || p.endsWith("_concrete") || p.endsWith("glazed_terracotta") || p.endsWith("_door")
                || p.endsWith("_trapdoor") || p.endsWith("_sign") || p.endsWith("_fence_gate")
                || (p.endsWith("_fence") && !p.startsWith("nether_brick")) || p.endsWith("_stained_glass")
                || p.endsWith("_stained_glass_pane") || p.endsWith("_banner")) return Sig.PLACED;
        if (p.endsWith("torch") || p.endsWith("lantern") || p.equals("glowstone") || p.equals("shroomlight")
                || p.equals("end_rod") || p.equals("campfire") || p.equals("soul_campfire")) return Sig.LIGHT;
        return null;
    }

    private double weight(Sig s) {
        return switch (s) {
            case MACHINE -> wMachines.value;
            case STORAGE -> wStorage.value;
            case FARM -> wFarming.value;
            case PLACED -> wPlaced.value;
            case LIGHT -> wLight.value;
            case ENTITY -> wEntities.value;
            case GROWTH -> wGrowth.value;
            case REDSTONE -> wRedstone.value;
            case PORTAL -> wPortals.value;
            case DECOR -> wDecor.value;
        } / 100.0;
    }

    private boolean on(Sig s) {
        return switch (s) {
            case MACHINE -> machines.value;
            case STORAGE -> storage.value;
            case FARM -> farming.value;
            case PLACED -> placed.value;
            case LIGHT -> light.value;
            case ENTITY -> entities.value;
            case GROWTH -> growth.value;
            case REDSTONE -> redstone.value;
            case PORTAL -> portals.value;
            case DECOR -> decor.value;
        };
    }

    @Override
    protected void onEnable() { timer = 1000; }

    @Override
    protected void onDisable() {
        dev.kuro.client.util.Finds.set("Base", List.of());
        infos.clear(); fresh = new HashMap<>(); display = List.of(); alerted.clear(); mineChunks.clear(); trialChunks.clear(); scanned.clear(); queue.clear(); queuePos = 0;
        liveSpots.clear(); seenAt.clear();
    }

    // ---------------- scanning (a few chunks per tick, so it never causes a lag spike) ----------------

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (mc.level != lastWorld) {                       // new world / dimension
            lastWorld = mc.level;
            infos.clear(); fresh = new HashMap<>(); display = List.of(); alerted.clear(); mineChunks.clear(); trialChunks.clear(); scanned.clear(); queue.clear(); queuePos = 0;
        liveSpots.clear(); seenAt.clear();
            timer = 1000;
        }

        // any setting changed (a "Sig:" check, weights, Ignore structures...): re-rate what we remember right away
        // and rescan, instead of waiting for the next pass, so turning a check on shows its reasons straight away
        long st = settingsStamp();
        if (st != stamp) {
            stamp = st;
            rebuildDisplay();
            if (queuePos >= queue.size()) timer = 1000;
        }

        tickLive();

        if (queuePos >= queue.size()) {
            if (++timer < passDelay.intValue()) return;     // a new pass every "Rescan delay" ticks (default 40 = ~2 seconds)
            timer = 0;
            beginPass();
            if (queue.isEmpty()) return;
        }

        // "Scan speed" chunks per tick, but never more than ~12 ms of work in one tick so a high value cannot freeze the game
        int speed = scanSpeed.intValue();
        long t0 = System.nanoTime();
        for (int n = 0; n < speed && queuePos < queue.size(); n++) {
            long[] c = queue.get(queuePos++);
            LevelChunk chunk = mc.level.getChunkSource().getChunk((int) c[0], (int) c[1], false);
            if (chunk != null) {
                Info i = scanChunk(chunk);
                fresh.put(i.key(), i);
                scanned.put(i.key(), System.identityHashCode(chunk));
            } else skipped++;
            if (System.nanoTime() - t0 > 12_000_000L) break;
        }
        if (queuePos >= queue.size()) finishPass();
    }

    private long stamp = Long.MIN_VALUE;
    private long passStart, lastPassMs;
    private int skipped;                                         // chunks in range that were not loaded yet this pass

    private long settingsStamp() {
        long h = 17;
        for (Setting s : settings) {
            long v = 0;
            if (s instanceof Setting.Bool b) v = b.value ? 1 : 0;
            else if (s instanceof Setting.Num n) v = Double.doubleToLongBits(n.value);
            else if (s instanceof Setting.Hue hu) v = Float.floatToIntBits(hu.hue) * 31L + Float.floatToIntBits(hu.sat) * 17L + Float.floatToIntBits(hu.val);
            h = h * 31 + v;
        }
        return h;
    }

    private void beginPass() {
        queue.clear(); queuePos = 0; fresh = new HashMap<>();
        passStart = System.nanoTime(); skipped = 0;
        ChunkPos cp = mc.player.chunkPosition();
        int rad = range.intValue();
        var cm = mc.level.getChunkSource();
        if (skipScanned.value) {
            // forget chunks that have been unloaded, so they are scanned again if they come back (their blocks may have changed)
            scanned.keySet().removeIf(k -> cm.getChunk(ChunkPos.getX(k), ChunkPos.getZ(k), false) == null);
        } else scanned.clear();
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++) {
                int x = cp.x + dx, z = cp.z + dz;
                if (skipScanned.value) {
                    LevelChunk loaded = cm.getChunk(x, z, false);
                    Integer seen = scanned.get(ChunkPos.asLong(x, z));
                    if (loaded != null && seen != null && seen == System.identityHashCode(loaded)) continue;   // already scanned
                }
                queue.add(new long[]{x, z});
            }
        queue.sort((a, b) -> Long.compare(
                (a[0] - cp.x) * (a[0] - cp.x) + (a[1] - cp.z) * (a[1] - cp.z),
                (b[0] - cp.x) * (b[0] - cp.x) + (b[1] - cp.z) * (b[1] - cp.z)));   // nearest chunks first
    }

    private boolean interesting(BlockState s) {
        Block b = s.getBlock();
        return classes.containsKey(b) || markers.containsKey(b) || b == Blocks.KELP;
    }

    private Info scanChunk(LevelChunk chunk) {
        Info info = new Info(chunk.getPos().x, chunk.getPos().z);
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();

        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(this::interesting)) continue;
            int baseY = (chunk.getMinSectionY() + i) << 4;
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        BlockState st = sec.getBlockState(x, y, z);
                        if (st.isAir()) continue;
                        Block b = st.getBlock();
                        Integer mk = markers.get(b);
                        if (mk != null) {
                            info.markers |= mk;
                            if (baseY + y < 50) {
                                if ((mk & M_WOOD) != 0) info.markers |= M_DWOOD;
                                if ((mk & M_FENCE) != 0) info.markers |= M_DFENCE;
                            }
                        }
                        Sig sg = classes.get(b);
                        if (b == Blocks.KELP && st.hasProperty(BlockStateProperties.AGE_25) && st.getValue(BlockStateProperties.AGE_25) >= 25)
                            sg = Sig.GROWTH;                       // fully grown kelp
                        if (sg == null) continue;
                        int wy = baseY + y;
                        double c = 1;
                        if (sg == Sig.LIGHT && wy >= 55) continue;  // only torches underground count
                        if (sg == Sig.FARM && wy < 45) c = 2;       // farmland / crops underground count double
                        info.add(sg, c, baseX + x + 0.5, wy + 0.5, baseZ + z + 0.5);
                    }
        }

        // storage: chests, barrels, shulker boxes, ender chests (a double chest counts once)
        int stores = 0, spawners = 0;
        List<BlockEntity> storeList = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof SpawnerBlockEntity) { spawners++; continue; }
            boolean store = be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                    || be instanceof ShulkerBoxBlockEntity || be instanceof EnderChestBlockEntity;
            if (!store) continue;
            BlockState st = be.getBlockState();
            if (st.hasProperty(ChestBlock.TYPE) && st.getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue;
            stores++;
            storeList.add(be);
        }
        if (!(spawners > 0 && stores <= 2)) {                     // a lone dungeon chest is natural
            for (BlockEntity be : storeList)
                info.add(Sig.STORAGE, 1, be.getBlockPos().getX() + 0.5, be.getBlockPos().getY() + 0.5, be.getBlockPos().getZ() + 0.5);
        }

        // shipwreck: in an ocean or beach, only wood (planks/trapdoors/fences) and a few chests, nothing a player would build
        if (!findWater.value && (info.markers & M_PLAYER) == 0 && ((info.markers & M_SHIPWOOD) != 0 || stores > 0)
                && info.counts[Sig.STORAGE.ordinal()] <= 2) {   // 3+ chests in one chunk is never a lone wreck / buried treasure
            var biome = mc.level.getNoiseBiome(baseX + 8, 62, baseZ + 8);
            info.shipRaw = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH);
        }
        return info;
    }

    private void finishPass() {
        lastPassMs = (System.nanoTime() - passStart) / 1_000_000L;
        // item frames, armor stands, named mobs and animal/villager pens, counted per chunk
        for (Entity e : mc.level.entitiesForRendering()) {
            double w = 0;
            if (e instanceof ItemFrame || e instanceof ArmorStand
                    || e instanceof net.minecraft.world.entity.decoration.painting.Painting) w = 1.5;
            else if (pets.value && ((e instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame())
                    || (e instanceof net.minecraft.world.entity.animal.equine.AbstractHorse h && h.isTamed())
                    || (e instanceof net.minecraft.world.entity.Mob m && m.isLeashed()))) w = 2.0;
            else if (e instanceof LivingEntity && !(e instanceof Player)) {
                if (e.hasCustomName()) w = 1.5;
                else if (e instanceof Animal || e instanceof Villager) w = 0.5;
            }
            if (w == 0) continue;
            Info i = fresh.get(ChunkPos.asLong(e.getBlockX() >> 4, e.getBlockZ() >> 4));
            if (i != null) i.add(Sig.ENTITY, w, e.getX(), e.getY(), e.getZ());
        }

        Set<Long> newStructure = new HashSet<>();                   // chunks that only now turned out to be mineshaft / trail chamber
        for (Info i : fresh.values()) {                             // remember which chunks look like mineshaft / trail chamber
            if (isMine(i.markers)) { if (mineChunks.add(i.key())) newStructure.add(i.key()); } else mineChunks.remove(i.key());
            if (isTrial(i.markers)) { if (trialChunks.add(i.key())) newStructure.add(i.key()); } else trialChunks.remove(i.key());
        }
        for (Info i : fresh.values()) {
            rate(i);
            if (i.own >= 0.12) infos.put(i.key(), i); else infos.remove(i.key());
        }
        // A chunk scanned earlier was rated before its neighbour (which shows the structure) was loaded: it was flagged
        // as a base. Now that the neighbour is known, rate those chunks again so the structure discount applies.
        if (!newStructure.isEmpty()) {
            List<Info> redo = new ArrayList<>();
            for (Info o : infos.values()) {
                if (fresh.containsKey(o.key())) continue;
                for (long k : newStructure) {
                    if (Math.abs(o.cx - (int) k) <= 2 && Math.abs(o.cz - (int) (k >>> 32)) <= 2) { redo.add(o); break; }
                }
            }
            for (Info o : redo) {
                rate(o);
                if (o.own >= 0.12) infos.put(o.key(), o); else infos.remove(o.key());
            }
        }
        fresh = new HashMap<>();
        rebuildDisplay();
        List<Info> sorted = display;

        // one-time alerts
        int sent = 0;
        for (Info i : sorted) {
            int pct = (int) Math.round(i.pct * 100);
            if (pct < alertAt.intValue()) break;
            if (!chatAlert.value && !soundAlert.value && !discordAlert.value) break;       // nothing to announce: keep chunks un-alerted for when it is switched on
            if (i.live != null) continue;                           // live players have their own alert
            if (alerted.contains(i.key())) continue;
            if (sent >= 3) break;                                   // the rest are announced on the next passes, not lost
            alerted.add(i.key());
            sent++;
            if (chatAlert.value) {
                mc.player.displayClientMessage(Component.literal("[Sus Chunk] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(pct + "% chance of a base near " + (int) i.midX() + ", " + (int) i.midZ()
                                + (i.reasons.isEmpty() ? "" : " (" + i.reasons + ")")).withStyle(ChatFormatting.YELLOW)), false);
            }
            if (soundAlert.value) mc.player.playSound(ALERT, 1.0f, 1.0f);
            if (discordAlert.value) {
                try {
                    sendDiscord(":house: **Possible base** (" + pct + "%) near X " + (int) i.midX() + ", Y " + (int) i.midY(mc.player.getY())
                            + ", Z " + (int) i.midZ() + (i.reasons.isEmpty() ? "" : "\nReasons: " + i.reasons) + serverSuffix(), false);
                } catch (Throwable ignored) { }   // Discord problems must never disturb the scan
            }
        }
    }

    // ---------------- Discord webhook ----------------

    private static final Path HOOK_FILE = Path.of("config", "kuro-discord-webhook.txt");
    private static HttpClient httpClient;
    private static synchronized HttpClient http() { if (httpClient == null) httpClient = HttpClient.newHttpClient(); return httpClient; }

    private static boolean validHook(String u) {
        return u.startsWith("https://discord.com/api/webhooks/") || u.startsWith("https://discordapp.com/api/webhooks/");
    }

    private void saveWebhook(String url) {
        String u = url.trim();
        if (!validHook(u)) {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Sus Chunk] Not a Discord webhook URL (must start with https://discord.com/api/webhooks/)").withStyle(ChatFormatting.RED), false);
            return;
        }
        try {
            Files.createDirectories(HOOK_FILE.getParent());
            Files.writeString(HOOK_FILE, u, StandardCharsets.UTF_8);
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Sus Chunk] Discord webhook saved.").withStyle(ChatFormatting.GREEN), false);
        } catch (Exception e) {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Sus Chunk] Could not save webhook: " + e.getMessage()).withStyle(ChatFormatting.RED), false);
        }
    }

    private String serverSuffix() {
        try {
            var sv = mc.getCurrentServer();
            return sv != null ? "\nServer: " + sv.ip : "";
        } catch (Throwable t) { return ""; }
    }

    private static String jsonEscape(String t) {
        StringBuilder b = new StringBuilder();
        for (char c : t.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> {}
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(' '); else b.append(c); }
            }
        }
        return b.toString();
    }

    // ---- bot DM (preferred when token + user ID are saved) ----
    private static final Path BOT_FILE = Path.of("config", "kuro-discord-bot.txt");   // line 1: bot token, line 2: your user ID
    private volatile String dmChannel;

    private void say(String msg, ChatFormatting c) {
        mc.execute(() -> { if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Sus Chunk] " + msg).withStyle(c), false); });
    }

    private String[] readBot() {
        try {
            if (!Files.exists(BOT_FILE)) return new String[]{"", ""};
            List<String> l = Files.readAllLines(BOT_FILE, StandardCharsets.UTF_8);
            return new String[]{l.size() > 0 ? l.get(0).trim() : "", l.size() > 1 ? l.get(1).trim() : ""};
        } catch (Exception e) { return new String[]{"", ""}; }
    }

    private void saveBot(String token, String userId) {
        String[] cur = readBot();
        if (token != null) cur[0] = token.trim();
        if (userId != null) {
            if (!userId.trim().matches("\\d{15,25}")) { say("User ID must be just numbers (enable Developer Mode, right-click yourself, Copy User ID).", ChatFormatting.RED); return; }
            cur[1] = userId.trim();
        }
        try {
            Files.createDirectories(BOT_FILE.getParent());
            Files.writeString(BOT_FILE, cur[0] + "\n" + cur[1] + "\n", StandardCharsets.UTF_8);
            dmChannel = null;
            say("Saved. Bot token " + (cur[0].isEmpty() ? "MISSING" : "set") + ", user ID " + (cur[1].isEmpty() ? "MISSING" : "set") + ".", ChatFormatting.GREEN);
        } catch (Exception e) { say("Could not save: " + e.getMessage(), ChatFormatting.RED); }
    }

    private static HttpRequest.Builder botReq(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header("Authorization", "Bot " + token).header("User-Agent", "DiscordBot (kuro, 1.0)");
    }

    /** Sends via bot DM if configured, else via webhook. Returns a short status string. Runs off the game thread. */
    private String deliver(String text) throws Exception {
        String[] bot = readBot();
        String uid = bot[1].matches("\\d{15,25}") ? bot[1] : "";
        // an @mention of you makes Discord send a real notification (red badge + push), not just a quiet message
        String mention = uid.isEmpty() ? "" : "<@" + uid + "> ";
        String allowed = uid.isEmpty() ? "" : ",\"allowed_mentions\":{\"users\":[\"" + uid + "\"]}";
        String body = "{\"content\":\"" + jsonEscape(mention + text) + "\"" + allowed + "}";
        if (!bot[0].isEmpty() && !bot[1].isEmpty()) {
            if (dmChannel == null) {
                HttpResponse<String> r = http().send(botReq("https://discord.com/api/v10/users/@me/channels", bot[0])
                        .POST(HttpRequest.BodyPublishers.ofString("{\"recipient_id\":\"" + bot[1] + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"").matcher(r.body());
                if (r.statusCode() / 100 != 2 || !m.find()) return "Bot could not open a DM (HTTP " + r.statusCode() + "): " + r.body();
                dmChannel = m.group(1);
            }
            HttpResponse<String> r = http().send(botReq("https://discord.com/api/v10/channels/" + dmChannel + "/messages", bot[0])
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) { dmChannel = null; return "Bot DM failed (HTTP " + r.statusCode() + "): " + r.body(); }
            return "DM sent";
        }
        String hook = Files.exists(HOOK_FILE) ? Files.readString(HOOK_FILE, StandardCharsets.UTF_8).trim() : "";
        if (!validHook(hook)) return "No Discord bot or webhook set yet.";
        HttpResponse<String> r = http().send(HttpRequest.newBuilder(URI.create(hook)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"Sus Chunk Finder\"," + body.substring(1), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        return r.statusCode() / 100 == 2 ? "Webhook message sent" : "Webhook failed (HTTP " + r.statusCode() + ")";
    }

    private void sendDiscord(String text, boolean reportResult) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            String res;
            try { res = deliver(text); } catch (Exception e) { res = "Discord send failed: " + e.getMessage(); }
            if (reportResult) say(res, res.startsWith("DM sent") || res.startsWith("Webhook message") ? ChatFormatting.GREEN : ChatFormatting.RED);
        });
    }

    // ---------------- live players ----------------

    /** Flags the chunk of every other player in view and tells you the first time each one shows up. */
    private void tickLive() {
        if (!flagPlayers.value) {
            if (!liveSpots.isEmpty()) { liveSpots.clear(); seenAt.clear(); rebuildDisplay(); }
            return;
        }
        long now = System.currentTimeMillis(), keep = (long) playerKeep.value * 1000L;
        boolean changed = false;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            if (ignoreFriends.value && Friends.is(p)) continue;
            String name = p.getName().getString();
            long key = ChunkPos.asLong(p.getBlockX() >> 4, p.getBlockZ() >> 4);
            Spot sp = liveSpots.get(key);
            if (sp == null) { liveSpots.put(key, new Spot(name, p.getX(), p.getY(), p.getZ(), now)); changed = true; }
            else { sp.name = name; sp.x = p.getX(); sp.y = p.getY(); sp.z = p.getZ(); sp.last = now; }
            Long prev = seenAt.get(p.getUUID());
            if (prev == null || now - prev > keep) notifyPlayer(name, p.getX(), p.getY(), p.getZ());
            seenAt.put(p.getUUID(), now);
        }
        for (Iterator<Spot> it = liveSpots.values().iterator(); it.hasNext(); )
            if (now - it.next().last > keep) { it.remove(); changed = true; }
        seenAt.values().removeIf(t -> now - t > keep);
        if (changed) rebuildDisplay();
    }

    private void notifyPlayer(String name, double x, double y, double z) {
        double dx = x - mc.player.getX(), dz = z - mc.player.getZ();
        long dist = Math.round(Math.sqrt(dx * dx + dz * dz));
        if (playerChat.value) {
            mc.player.displayClientMessage(Component.literal("[Sus Chunk] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("LIVE PLAYER " + name + " at " + (int) x + ", " + (int) z + " (chunk "
                            + ((int) Math.floor(x) >> 4) + ", " + ((int) Math.floor(z) >> 4) + ", " + dist + "m away)").withStyle(ChatFormatting.WHITE)), false);
        }
        if (playerSound.value) mc.player.playSound(ALERT, 1.0f, 1.0f);
        if (discordAlert.value && discordPlayers.value) try {
            sendDiscord(":eyes: **Player spotted:** " + name + " at X " + (int) x + ", Z " + (int) z + " (" + dist + "m away)" + serverSuffix(), false);
        } catch (Throwable ignored) { }
    }

    /** Re-rates every remembered chunk plus every flagged chunk, spreads a little score into neighbours and sorts. */
    private void rebuildDisplay() {
        Map<Long, Info> all = new HashMap<>(infos);
        for (Map.Entry<Long, Spot> e : liveSpots.entrySet()) {
            all.computeIfAbsent(e.getKey(), k -> {
                Info i = new Info(ChunkPos.getX(k), ChunkPos.getZ(k));
                i.hx = e.getValue().x; i.hy = e.getValue().y; i.hz = e.getValue().z; i.hw = 1;   // no block evidence: centre on the player
                return i;
            });
        }
        for (Info i : all.values()) rate(i);                        // also re-applies the current toggles to remembered chunks

        // a base spreads across chunk borders: neighbours add a little
        for (Info i : all.values()) {
            double none = 1 - i.own;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Info n = all.get(ChunkPos.asLong(i.cx + dx, i.cz + dz));
                    if (n != null) none *= 1 - 0.35 * n.own;
                }
            i.pct = Math.min(0.99, 1 - none);
        }
        List<Info> sorted = new ArrayList<>(all.values());
        sorted.sort((a, b) -> Double.compare(b.pct, a.pct));
        display = sorted;
        publish(sorted);
    }

    /** Shares the strongest results with the Found List screen. */
    private void publish(List<Info> sorted) {
        List<dev.kuro.client.util.Finds.Find> out = new ArrayList<>();
        double py = mc.player != null ? mc.player.getY() : 64;
        for (Info i : sorted) {
            int pct = (int) Math.round(i.pct * 100);
            if (i.live == null && pct < minPercent.intValue()) continue;
            if (out.size() >= 100) break;
            String label = (i.live != null ? "Player " + i.live.name : pct + "% base") + (i.reasons.isEmpty() ? "" : " (" + i.reasons + ")");
            out.add(new dev.kuro.client.util.Finds.Find("Base", label, (int) i.midX(), (int) i.midY(py), (int) i.midZ(), pct));
        }
        dev.kuro.client.util.Finds.set("Base", out);
    }

    /** Turns a chunk's counts into its own probability-like score and a short list of reasons. */
    private void rate(Info i) {
        boolean mine = isMine(i.markers);
        for (int dx = -2; dx <= 2 && !mine; dx++)                   // a mineshaft continues into the next chunks (corridors are long)
            for (int dz = -2; dz <= 2 && !mine; dz++)
                if (mineChunks.contains(ChunkPos.asLong(i.cx + dx, i.cz + dz))) mine = true;
        boolean trial = isTrial(i.markers);
        for (int dx = -2; dx <= 2 && !trial; dx++)                   // a trail chamber is big: it spans several chunks
            for (int dz = -2; dz <= 2 && !trial; dz++)
                if (trialChunks.contains(ChunkPos.asLong(i.cx + dx, i.cz + dz))) trial = true;
        i.trial = ignoreStructures.value && trial;
        i.mine = ignoreStructures.value && mine;
        i.ship = ignoreStructures.value && i.shipRaw;
        i.structure = ignoreStructures.value && (mine || trial || i.ship || (i.markers & M_STRUCT) != 0);
        double none = 1;
        Sig[] all = Sig.values();
        double[] contrib = new double[all.length];
        for (Sig s : all) {
            if (!on(s)) continue;
            // mineshafts are full of planks/fences/torches and shipwrecks of planks/chests: those never count there
            boolean zero = (i.trial && s != Sig.FARM && s != Sig.PORTAL && s != Sig.GROWTH)   // dispensers, chests, pots, copper, lanterns: all natural there
                    || (i.mine && (s == Sig.PLACED || s == Sig.LIGHT || s == Sig.DECOR)) || (i.ship && (s == Sig.PLACED || s == Sig.STORAGE || s == Sig.DECOR));
            double scale = zero ? 0.0 : i.structure ? s.structScale : 1.0;
            double c = i.counts[s.ordinal()] * scale;
            double p = Math.min(0.99, s.reliability * weight(s) * (1 - Math.exp(-c / s.k)));
            contrib[s.ordinal()] = p;
            none *= 1 - p;
        }
        i.live = liveSpots.get(i.key());
        if (i.live != null) none *= 1 - LIVE_RELIABILITY;
        i.own = 1 - none;

        // reasons, strongest first: a short line for chat alerts and the full list for the hover tooltip
        i.rows.clear();
        StringBuilder sb = new StringBuilder();
        boolean[] used = new boolean[all.length];
        int shown = 0;
        i.signals = 0;
        for (int n = 0; n < all.length; n++) {
            int best = -1;
            for (int k = 0; k < all.length; k++)
                if (!used[k] && contrib[k] >= 0.04 && (best < 0 || contrib[k] > contrib[best])) best = k;
            if (best < 0) break;
            used[best] = true;
            i.signals++;
            long cnt = Math.round(i.counts[best]);
            i.rows.add(new String[]{"+" + Math.round(contrib[best] * 100), all[best].label + " " + cnt});
            if (contrib[best] >= 0.08 && shown++ < 3) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(all[best].label).append(' ').append(cnt);
            }
        }
        if (i.live != null) {
            i.rows.add(0, new String[]{"+" + Math.round(LIVE_RELIABILITY * 100), "live player  " + i.live.name});
            sb.insert(0, "player " + i.live.name + (sb.length() > 0 ? " | " : ""));
        }
        if (i.structure) {
            String t = i.trial ? "trail chamber" : i.mine ? "near mineshaft" : i.ship ? "shipwreck?" : "near structure?";
            i.rows.add(new String[]{"-", t + " (discounted)"});
            sb.append(sb.length() > 0 ? " | " : "").append(t);
        }
        i.reasons = sb.toString();
    }

    // ---------------- drawing ----------------

    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final double AIM_PX = 9;   // GUI pixels around the crosshair that count as "on" a marker dot

    /** Height the marker is drawn at: the clues' real height, or the flat plane (world bottom / your feet). */
    private double markerY(Info i, double py) {
        if (atBase.value && i.hw > 0) return i.midY(py);
        return flatFloor.value ? mc.level.getMinY() + 0.05 : Math.floor(py) + 0.02;
    }

    private static String compass(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));             // 0 = north, 90 = east (+Z is south in Minecraft)
        return COMPASS[(int) Math.round(((a % 360) + 360) % 360 / 45.0) % 8];
    }

    private static int colorOf(Info i, int pct) { return i.live != null ? LIVE : Look.accentOverride ? Look.ACCENT : tierColor(pct); }

    private record Vis(Info info, int pct, double dist) {}

    // what the world pass decided this frame; the HUD pass reads it for the tooltip, markers and tracer
    private List<Vis> curVis = List.of();
    private Vis curHover;
    private Cluster curHoverCluster;
    private List<Cluster> curClusters = List.of();

    /** Touching suspect chunks drawn as one solid outline; the hover callout lists everything found in all of them. */
    private static final class Cluster {
        final List<Vis> members = new ArrayList<>();
        final java.util.Set<Long> cells = new java.util.HashSet<>();   // chunk keys
        Vis best;                                                       // most suspicious member: colour, percent and the marker dot
        int pct;
        double dist;                                                    // nearest member
        double cx, cz, hx, hz;                                          // bounding box centre / half size in blocks
        double scale = 1.0;                                             // far-away growth (see "Bigger when far %")
        long id = Long.MAX_VALUE;                                       // smallest chunk key: stable while the cluster is the same
        boolean has(Info i) { for (Vis v : members) if (v.info == i) return true; return false; }
    }

    /**
     * World pass: works out which chunks are in range / aimed at and queues their boxes. They are drawn by WorldGfx
     * in a single GPU call through the game's own projection (Meteor-style), so they stay glued to the world and
     * cost almost nothing, instead of hundreds of GUI rectangles per frame.
     */
    @Override
    public void onWorld() {
        curVis = List.of();
        curHover = null;
        curHoverCluster = null;
        curClusters = List.of();
        List<Info> list = display;
        if (list.isEmpty()) return;

        Vec3 c = Projector.camPos;
        double maxD = maxDistance.value, max2 = maxD * maxD;
        int min = minPercent.intValue(), limit = maxHighlights.intValue();
        double py = mc.player.getY();

        // 1) every suspected chunk (or flagged player chunk) in range, nearest first
        List<Vis> vis = new ArrayList<>();
        for (Info i : list) {
            int pct = (int) Math.round(i.pct * 100);
            if (i.live == null && pct < min) continue;
            double dx = i.midX() - c.x, dy = i.midY(py) - c.y, dz = i.midZ() - c.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > max2) continue;
            vis.add(new Vis(i, pct, Math.sqrt(d2)));
        }
        if (vis.isEmpty()) return;
        vis.sort((a, b) -> Double.compare(a.dist, b.dist));
        if (vis.size() > limit) {                                   // keep the most suspicious ones (flagged chunks always stay)
            List<Vis> keep = new ArrayList<>();
            List<Vis> rest = new ArrayList<>();
            for (Vis v : vis) (v.info.live != null ? keep : rest).add(v);
            rest.sort((a, b) -> Double.compare(b.pct, a.pct));
            for (int k = 0; k < rest.size() && keep.size() < limit; k++) keep.add(rest.get(k));
            keep.sort((a, b) -> Double.compare(a.dist, b.dist));
            vis = keep;
        }

        // 2) which one is under the crosshair? Only the little dot drawn in the middle of a chunk counts: the info card
        //    shows up when the crosshair is right on that dot (it is found through walls, like the dot itself).
        Vis hover = null;
        if (hoverInfo.value && mc.screen == null) {
            double bestKey = Double.MAX_VALUE;
            double scx = Projector.width() / 2.0, scy = Projector.height() / 2.0;
            double[] sp = new double[2];
            for (Vis v : vis) {
                if (!Projector.project(v.info.midX(), markerY(v.info, py), v.info.midZ(), sp)) continue;
                double pd = Math.hypot(sp[0] - scx, sp[1] - scy);
                if (pd <= AIM_PX && pd < bestKey) { bestKey = pd; hover = v; }
            }
        }
        curVis = vis;
        curHover = hover;

        // 3) group touching chunks into clusters, size each cluster so far ones stay readable without overlapping their
        //    neighbours, then queue ONE solid outline per cluster (edges between its own chunks are not drawn) plus a dot.
        List<Cluster> clusters = buildClusters(vis);
        sizeClusters(clusters);
        curClusters = clusters;
        curHoverCluster = null;
        for (Cluster cl : clusters) {
            boolean hot = hover != null && cl.has(hover.info);
            if (hot) curHoverCluster = cl;
            int col = colorOf(cl.best.info, cl.pct);
            double y = markerY(cl.best.info, py);
            drawOutline(cl, y, col, hot);
            WorldGfx.glowDot(cl.best.info.midX(), y, cl.best.info.midZ(), Look.withAlpha(col, 0xFF));
        }
    }

    // ---------------- clusters ----------------

    private List<Cluster> buildClusters(List<Vis> vis) {
        int n = vis.size();
        int[] parent = new int[n];
        for (int k = 0; k < n; k++) parent[k] = k;
        if (mergeChunks.value) {
            Map<Long, Integer> at = new HashMap<>();
            for (int k = 0; k < n; k++) at.put(vis.get(k).info.key(), k);
            for (int k = 0; k < n; k++) {
                Info i = vis.get(k).info;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Integer m = at.get(ChunkPos.asLong(i.cx + dx, i.cz + dz));
                        if (m == null) continue;
                        int ra = find(parent, k), rb = find(parent, m);
                        if (ra != rb) parent[ra] = rb;
                    }
            }
        }
        Map<Integer, Cluster> byRoot = new HashMap<>();
        List<Cluster> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            Vis v = vis.get(k);
            Cluster cl = byRoot.computeIfAbsent(find(parent, k), r -> { Cluster c = new Cluster(); out.add(c); return c; });
            cl.members.add(v);
            cl.cells.add(v.info.key());
            cl.id = Math.min(cl.id, v.info.key());
            if (cl.best == null || v.pct > cl.best.pct || (v.info.live != null && cl.best.info.live == null)) cl.best = v;
            cl.dist = cl.members.size() == 1 ? v.dist : Math.min(cl.dist, v.dist);
        }
        for (Cluster cl : out) {
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (Vis v : cl.members) {
                x0 = Math.min(x0, v.info.cx); x1 = Math.max(x1, v.info.cx);
                z0 = Math.min(z0, v.info.cz); z1 = Math.max(z1, v.info.cz);
            }
            cl.pct = cl.best.pct;
            cl.cx = (x0 + x1 + 1) * 8.0; cl.cz = (z0 + z1 + 1) * 8.0;
            cl.hx = (x1 - x0 + 1) * 8.0; cl.hz = (z1 - z0 + 1) * 8.0;
        }
        out.sort((a, b) -> Double.compare(a.dist, b.dist));
        return out;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }

    /**
     * Far clusters grow around their centre in proportion to their flat distance ("Bigger when far %"), then any two
     * that would overlap are shrunk back (never below true size) until their outlines are clear of each other.
     */
    private void sizeClusters(List<Cluster> cl) {
        Vec3 c = Projector.camPos;
        for (Cluster k : cl) {
            double g = 1.0;
            if (farGrowth.value > 0) {
                double dx = k.cx - c.x, dz = k.cz - c.z;
                g = Math.min(12.0, 1.0 + (farGrowth.value / 100.0) * Math.sqrt(dx * dx + dz * dz) / 50.0);
            }
            k.scale = g;
        }
        final double gap = 3.0;                                     // blocks of air kept between neighbouring outlines
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            for (int a = 0; a < cl.size(); a++)
                for (int b = a + 1; b < cl.size(); b++) {
                    Cluster p = cl.get(a), q = cl.get(b);
                    double sx = p.hx * p.scale + q.hx * q.scale, sz = p.hz * p.scale + q.hz * q.scale;
                    double dx = Math.abs(p.cx - q.cx), dz = Math.abs(p.cz - q.cz);
                    if (dx >= sx + gap || dz >= sz + gap) continue;             // already clear on one axis
                    double f = Math.max((dx - gap) / sx, (dz - gap) / sz);       // shrink both until clear on the easier axis
                    f = Mth.clamp(f, 0.0, 0.999);
                    p.scale = Math.max(1.0, p.scale * f);
                    q.scale = Math.max(1.0, q.scale * f);
                    changed = true;
                }
            if (!changed) break;
        }
    }

    /** Solid outline of the union of a cluster's chunks (only edges that have no neighbour chunk), scaled about its centre. */
    private void drawOutline(Cluster cl, double y, int col, boolean hot) {
        int line = Look.withAlpha(col, hot ? 0xFF : 0xE0), glow = Look.withAlpha(col, hot ? 0x40 : 0x28);
        int th = hot ? 2 : 1;
        java.util.Set<Long> north = new java.util.HashSet<>(), south = new java.util.HashSet<>(),
                west = new java.util.HashSet<>(), east = new java.util.HashSet<>();
        for (Vis v : cl.members) {
            int x = v.info.cx, z = v.info.cz;
            if (!cl.cells.contains(ChunkPos.asLong(x, z - 1))) north.add(ChunkPos.asLong(x, z));
            if (!cl.cells.contains(ChunkPos.asLong(x, z + 1))) south.add(ChunkPos.asLong(x, z));
            if (!cl.cells.contains(ChunkPos.asLong(x - 1, z))) west.add(ChunkPos.asLong(x, z));
            if (!cl.cells.contains(ChunkPos.asLong(x + 1, z))) east.add(ChunkPos.asLong(x, z));
        }
        runs(cl, north, true, 0, y, line, glow, th);
        runs(cl, south, true, 1, y, line, glow, th);
        runs(cl, west, false, 0, y, line, glow, th);
        runs(cl, east, false, 1, y, line, glow, th);
    }

    /** Joins neighbouring edge cells into long straight lines, so corners and joints are drawn once. */
    private void runs(Cluster cl, java.util.Set<Long> cells, boolean alongX, int far, double y, int line, int glow, int th) {
        for (long key : cells) {
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            long prev = alongX ? ChunkPos.asLong(x - 1, z) : ChunkPos.asLong(x, z - 1);
            if (cells.contains(prev)) continue;                                  // not the start of a run
            int len = 1;
            while (cells.contains(alongX ? ChunkPos.asLong(x + len, z) : ChunkPos.asLong(x, z + len))) len++;
            double ax, az, bx, bz;
            if (alongX) { ax = x * 16.0; bx = (x + len) * 16.0; az = bz = (z + far) * 16.0; }
            else        { az = z * 16.0; bz = (z + len) * 16.0; ax = bx = (x + far) * 16.0; }
            ax = cl.cx + (ax - cl.cx) * cl.scale; bx = cl.cx + (bx - cl.cx) * cl.scale;
            az = cl.cz + (az - cl.cz) * cl.scale; bz = cl.cz + (bz - cl.cz) * cl.scale;
            WorldGfx.flatLine(ax, az, bx, bz, y, glow, th + 3);
            WorldGfx.flatLine(ax, az, bx, bz, y, line, th);
        }
    }

    /** HUD pass: region map, live-player markers, tracer and the tooltip (all 2D). The boxes are in onWorld(). */
    @Override
    public void onHud(GuiGraphics g) {
        hudBody(g);
        tickTooltip(g, mc.font, curHoverCluster);
    }

    private void hudBody(GuiGraphics g) {
        boolean editing = dev.kuro.client.gui.HudEditScreen.editing();   // layout editor: draw the panels even with nothing found, so they can be moved
        if (progress.value && mc.level != null) drawProgress(g);
        List<Info> list = display;
        if (list.isEmpty()) {
            if (editing && mc.player != null) {
                if (regionMap.value) drawMap(g, list);
                if (indicator.value) drawIndicator(g, mc.font, List.of());
            }
            return;
        }
        if (regionMap.value) drawMap(g, list);

        List<Vis> vis = curVis;
        if (vis.isEmpty()) {
            if (editing && indicator.value) drawIndicator(g, mc.font, vis);
            return;
        }
        double py = mc.player.getY();

        // flagged players keep an on-screen marker even when you are not looking at them
        Font tr = mc.font;
        for (Vis v : vis) {
            if (v.info.live == null) continue;
            Projector.projectOrPoint(v.info.live.x, v.info.live.y + 2.3, v.info.live.z, o);
            Look.tagClamped(g, tr, "LIVE " + v.info.live.name + "  " + (int) v.dist + "m", (int) o[0], (int) o[1] - 14, LIVE);
        }

        if (tracerBest.value) {
            Vis best = null;
            for (Vis v : vis) if (best == null || v.pct > best.pct) best = v;
            if (best != null) tracer(g, best.info.midX(), best.info.midY(py), best.info.midZ(), best.pct);
        }

        if (indicator.value) drawIndicator(g, tr, vis);
    }

    // ---- smooth open / close of the hover callout ----
    private float tipT;                 // 0 = closed, 1 = fully open
    private Cluster tipCl;              // what the callout is showing (kept while it closes)
    private long tipNanos;

    private void tickTooltip(GuiGraphics g, Font tr, Cluster hover) {
        long now = System.nanoTime();
        float dt = tipNanos == 0 ? 0f : Math.min(0.1f, (now - tipNanos) / 1e9f);
        tipNanos = now;
        if (hover != null) {
            if (tipCl == null || tipCl.id != hover.id) { if (tipCl == null || tipT <= 0.02f) tipT = 0f; }
            tipCl = hover;                                         // live numbers (distance, %) keep updating
            tipT = Math.min(1f, tipT + dt / 0.16f);                // ~160 ms to open
        } else if (tipCl != null) {
            tipT = Math.max(0f, tipT - dt / 0.11f);                // ~110 ms to close
            if (tipT <= 0f) tipCl = null;
        }
        if (tipCl == null || tipT <= 0f) return;
        float t = tipT, e = 1f - (1f - t) * (1f - t) * (1f - t);   // ease-out cubic
        try { drawChip(g, tipCl, e, true, null); }
        catch (RuntimeException ex) {
            if (!tooltipErrorShown) {
                tooltipErrorShown = true;
                mc.player.displayClientMessage(Component.literal("[Sus Chunk] hover info failed: " + ex), false);
            }
        }
    }

    private static int fade(int argb, float k) {
        return Look.withAlpha(argb, Math.round(((argb >>> 24) & 0xFF) * k));
    }

    /** Top-left readout: how far this pass is, how many chunks were not loaded, how long the last pass took. */
    private void drawProgress(GuiGraphics g) {
        Font tr = mc.font;
        boolean scanning = queuePos < queue.size();
        double frac = queue.isEmpty() ? 1.0 : Math.min(1.0, queuePos / (double) queue.size());
        String s = "Sus scan " + (scanning ? queuePos + "/" + queue.size() : "idle")
                + (skipped > 0 ? "  " + skipped + " unloaded" : "")
                + (lastPassMs > 0 ? "  last " + String.format("%.1f", lastPassMs / 1000.0) + "s" : "");
        int col = lastPassMs > 8000 ? Look.BAD : lastPassMs > 4000 ? Look.WARN : Look.GOOD;   // slow passes = not keeping up
        int w = SdfText.w(s) + 16;
        pProgress.place(4, 18, w, 17);
        int x = pProgress.x(), y = pProgress.y();
        Look.panel(g, x, y, x + w, y + 17, col);
        SdfText.ui(g, s, x + 8, y + 3, Look.TEXT, false);
        Look.bar(g, x + 8, y + 13, w - 16, 2, frac, col);
    }

    /** Bottom-right cards: a player head when a player is flagged, a chest when a base is detected. */
    private void drawIndicator(GuiGraphics g, Font tr, List<Vis> vis) {
        Vis player = null, base = null;
        for (Vis v : vis) {
            if (v.info.live != null) { if (player == null || v.dist < player.dist) player = v; }
            else if (v.pct >= baseAt.intValue() && (base == null || v.pct > base.pct)) base = v;
        }
        int rows = (player != null ? 1 : 0) + (base != null ? 1 : 0);
        boolean demo = rows == 0 && dev.kuro.client.gui.HudEditScreen.editing();   // layout editor: one stand-in card
        if (rows == 0 && !demo) return;
        if (demo) rows = 1;
        int w = 130, h = 24;
        pIndicator.place(Projector.width() - w - 8, Projector.height() - 34 - rows * (h + 4), w, rows * (h + 4) - 4);
        int x = pIndicator.x();
        int y = pIndicator.y();
        if (demo) {
            Look.panel(g, x, y, x + w, y + h, Look.MUTED);
            g.renderItem(new ItemStack(Items.CHEST), x + 4, y + 4);
            SdfText.ui(g, "Base  80%", x + 26, y + 4, Look.MUTED, false);
            SdfText.ui(g, "N 120m", x + 26, y + 13, Look.MUTED, false);
            return;
        }
        if (player != null) {
            int c = colorOf(player.info, player.pct);
            Look.panel(g, x, y, x + w, y + h, c);
            g.renderItem(new ItemStack(Items.PLAYER_HEAD), x + 4, y + 4);
            SdfText.ui(g, "Player", x + 26, y + 4, c, false);
            SdfText.ui(g, player.info.live.name + "  " + (int) player.dist + "m", x + 26, y + 13, Look.MUTED, false);
            y += h + 4;
        }
        if (base != null) {
            int c = colorOf(base.info, base.pct);
            Look.panel(g, x, y, x + w, y + h, c);
            g.renderItem(new ItemStack(Items.CHEST), x + 4, y + 4);
            SdfText.ui(g, "Base  " + base.pct + "%", x + 26, y + 4, c, false);
            SdfText.ui(g, compass(base.info.midX() - Projector.camPos.x, base.info.midZ() - Projector.camPos.z) + " " + (int) base.dist + "m", x + 26, y + 13, Look.MUTED, false);
        }
    }

    private boolean tooltipErrorShown;

    /**
     * Slim chip: [dot] title  percent  X, Z coordinates ........ distance. While aimed at (k 0 -> 1) it grows downward into
     * a card that also lists every clue found in the whole cluster (counts added up across its chunks).
     * @param force draw even when the marker is off screen (the aimed-at one then sits under the crosshair)
     * @return the rectangle drawn {x1, y1, x2, y2}, or null if it was skipped
     */
    private int[] drawChip(GuiGraphics g, Cluster cl, float k, boolean force, List<int[]> placed) {
        final SdfText.Face B = SdfText.Face.BOLD, S = SdfText.Face.SANS;
        final float T = SdfText.BASE;
        final int HH = 24;                                                            // chip height
        Info best = cl.best.info;
        int col = colorOf(best, cl.pct);
        String title = best.live != null ? "Live player" : cl.pct >= 75 ? "Very likely base" : cl.pct >= 50 ? "Likely base" : "Possible base";
        String pctS = cl.pct + "%";
        String coords = (int) Math.floor(best.midX()) + ", " + (int) Math.floor(best.midZ());   // real X, Z of the marker
        String dist = (int) cl.dist + "m";
        float headW = 10 + 6 + 8 + SdfText.width(B, title, T) + 8 + SdfText.width(B, pctS, T) + 12
                + SdfText.width(S, coords, T) + 18 + SdfText.width(S, dist, T) + 12;

        boolean open = k > 0.01f;
        List<String[]> rows = open ? mergedRows(cl) : List.of();
        String mergedS = cl.members.size() > 1 ? cl.members.size() + " chunks merged" : "";
        float ptsW = 0f, bodyW = 0f;
        for (String[] r : rows) ptsW = Math.max(ptsW, SdfText.width(B, r[0], T));
        for (String[] r : rows) bodyW = Math.max(bodyW, ptsW + 8f + SdfText.width(S, r[1], T));
        bodyW = Math.max(bodyW, SdfText.width(S, mergedS, SdfText.BASE));
        int w = (int) Math.ceil(Math.max(headW, open ? bodyW + 26f : 0f));
        int bodyH = rows.isEmpty() ? 0 : 8 + rows.size() * 13 + (mergedS.isEmpty() ? 0 : 13) + 4;
        int h = Math.round(HH + bodyH * k);

        double[] cp = new double[2];
        double scrW = Projector.width(), scrH = Projector.height();
        boolean onScreen = Projector.project(best.midX(), markerY(best, mc.player.getY()), best.midZ(), cp)
                && Double.isFinite(cp[0]) && Double.isFinite(cp[1])
                && cp[0] > 0 && cp[0] < scrW && cp[1] > 0 && cp[1] < scrH;
        if (!onScreen && !force) return null;
        int x, y;
        if (onScreen) { x = (int) Math.round(cp[0] - w / 2.0); y = (int) Math.round(cp[1]) - 16 - h; }
        else { x = (int) Math.round(scrW / 2.0 - w / 2.0); y = (int) Math.round(scrH / 2.0) + 18; }
        x = Mth.clamp(x, 4, Math.max(4, (int) scrW - w - 4));
        y = Mth.clamp(y, 4, Math.max(4, (int) scrH - h - 4));
        int[] rect = {x, y, x + w, y + h};
        if (placed != null) {
            for (int[] q : placed)
                if (rect[0] < q[2] + 3 && rect[2] > q[0] - 3 && rect[1] < q[3] + 3 && rect[3] > q[1] - 3) return null;
            placed.add(rect);
        }

        if (onScreen) {                                                               // short stem from the chip down to its marker
            double sx = Mth.clamp(cp[0], x + 10, x + w - 10);
            Gfx.line(g, sx, y + h, cp[0], cp[1] - 5, 1, Look.withAlpha(col, 0x90));
        }
        float r = Mth.lerp(k, HH / 2f, 10f);                                          // pill -> card corners
        Ui.panel(g, x, y, x + w, y + h, Math.round(r), Look.BG, Look.EDGE, 0, false);

        int ty = y + 7;
        Ui.corners(g, x + 10, y + 9, x + 16, y + 15, 3, col, true, true, true, true);
        float cx = x + 24;
        SdfText.draw(g, B, title, cx, ty, T, 0xFFFFFFFF);
        cx += SdfText.width(B, title, T) + 8;
        SdfText.draw(g, B, pctS, cx, ty, T, col);
        cx += SdfText.width(B, pctS, T) + 12;
        SdfText.draw(g, S, coords, cx, ty, T, Look.MUTED);
        SdfText.draw(g, S, dist, x + w - 12 - SdfText.width(S, dist, T), ty, T, Look.MUTED);   // distance: top right

        if (open && bodyH > 0) {
            float a = k * k;                                                          // body fades in as the card grows
            Ui.corners(g, x + 12, y + HH - 1, x + w - 12, y + HH, 1, fade(0x30FFFFFF, a), true, true, true, true);
            float ry = y + HH + 7;
            for (String[] row : rows) {
                boolean plus = row[0].startsWith("+");
                SdfText.draw(g, B, row[0], x + 13, ry, T, fade(plus ? 0xFFFFFFFF : Look.MUTED, a));
                SdfText.draw(g, S, row[1], x + 13 + ptsW + 8f, ry, T, fade(plus ? Look.TEXT : Look.MUTED, a));
                ry += 13;
            }
            if (!mergedS.isEmpty()) SdfText.draw(g, S, mergedS, x + 13, ry, SdfText.BASE, fade(Look.MUTED, a));
        }
        return rect;
    }

    /**
     * One clue list for the whole cluster: the same kind of clue found in several chunks is added up (x14 farm), keeping
     * the strongest chunk's points; live-player and "discounted" lines are listed once.
     */
    private static List<String[]> mergedRows(Cluster cl) {
        java.util.Map<String, double[]> counted = new java.util.LinkedHashMap<>();    // label -> {points, count}
        java.util.Map<String, String[]> special = new java.util.LinkedHashMap<>();     // live player lines
        java.util.Map<String, String[]> negative = new java.util.LinkedHashMap<>();    // discount lines
        for (Vis v : cl.members)
            for (String[] r : v.info.rows) {
                boolean plus = r[0].startsWith("+");
                if (!plus) { negative.putIfAbsent(r[1], r); continue; }
                String txt = r[1];
                int sp = txt.lastIndexOf(' ');
                try {
                    long cnt = Long.parseLong(txt.substring(sp + 1));
                    double pts = Double.parseDouble(r[0].substring(1));
                    double[] a = counted.computeIfAbsent(txt.substring(0, sp).trim(), key -> new double[2]);
                    a[0] = Math.max(a[0], pts);
                    a[1] += cnt;
                } catch (RuntimeException ex) {                                         // "live player  name": no trailing count
                    special.putIfAbsent(txt, r);
                }
            }
        List<String[]> out = new ArrayList<>(special.values());
        List<java.util.Map.Entry<String, double[]>> sorted = new ArrayList<>(counted.entrySet());
        sorted.sort((x, y) -> Double.compare(y.getValue()[0], x.getValue()[0]));
        for (var e : sorted)
            out.add(new String[]{"+" + Math.round(e.getValue()[0]), e.getKey() + " x" + Math.round(e.getValue()[1])});
        out.addAll(negative.values());
        return out;
    }

    private static int tierColor(int pct) {
        return pct >= 75 ? Look.BAD : pct >= 50 ? Look.ORANGE : pct >= 25 ? Look.WARN : 0xFF7C8594;
    }

    /** A line from the crosshair to the most suspicious chunk. */
    private void tracer(GuiGraphics g, double x, double y, double z, int pct) {
        double[] p = new double[2];
        Projector.projectOrPoint(x, y, z, p);
        Gfx.line(g, Projector.width() / 2.0, Projector.height() / 2.0, p[0], p[1], 1, Look.withAlpha(tierColor(pct), 0xB4));
    }

    /** Top-down map of every remembered suspicious chunk around you (north is up, white dot is you). */
    private void drawMap(GuiGraphics g, List<Info> list) {
        int r = mapRadius.intValue(), cell = mapCell.intValue(), size = (r * 2 + 1) * cell;
        // the draggable box is the plate plus the "N" above it
        pMap.place(Projector.width() - size - 17, Math.max(30, (Projector.height() - size) / 2) - 15, size + 10, size + 20);
        int x0 = pMap.x() + 5, y0 = pMap.y() + 15;
        Look.plate(g, x0 - 5, y0 - 5, x0 + size + 5, y0 + size + 5);
        Ui.corners(g, x0, y0, x0 + size, y0 + size, 3, 0x40000000, true, true, true, true);
        int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
        for (Info i : list) {
            int dx = i.cx - pcx, dz = i.cz - pcz;
            if (Math.abs(dx) > r || Math.abs(dz) > r) continue;
            int pct = (int) Math.round(i.pct * 100);
            int px = x0 + (dx + r) * cell, py = y0 + (dz + r) * cell;
            Ui.corners(g, px, py, px + cell - 1, py + cell - 1, Math.min(2, (cell - 1) / 2), Look.withAlpha(colorOf(i, pct), 0xE6), true, true, true, true);
        }
        int me = r * cell;
        Ui.corners(g, x0 + me - 1, y0 + me - 1, x0 + me + cell, y0 + me + cell, (cell + 2) / 2, 0xFF000000, true, true, true, true);        // you: white dot, dark rim
        Ui.corners(g, x0 + me, y0 + me, x0 + me + cell - 1, y0 + me + cell - 1, Math.max(1, (cell - 1) / 2), 0xFFFFFFFF, true, true, true, true);
        SdfText.ui(g, "N", x0 + size / 2 - 2, y0 - 15, Look.TEXT, true);
    }
}
