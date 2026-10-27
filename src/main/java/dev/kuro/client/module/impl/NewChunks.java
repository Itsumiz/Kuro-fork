package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Marks chunks that look newly generated. A fluid that has only just started to flow is the tell: the server sends
 * block updates for flowing water/lava in chunks that were just created, but not in chunks loaded from older saves.
 * It is an estimate (player-made water can trigger it too), and only chunks with some water or lava can be detected.
 * Detected chunks are saved per server and dimension, so a trail of new chunks shows where people have travelled.
 */
public class NewChunks extends Module {
    private static NewChunks instance;

    private final Setting.Num range = add(new Setting.Num("Range (chunks)", 8, 2, 16, 1));
    private final Setting.Bool outline = add(new Setting.Bool("Outline", true));
    private final Setting.Bool fill = add(new Setting.Bool("Fill", true));
    private final Setting.Num fillOpacity = add(new Setting.Num("Fill opacity", 40, 10, 150, 10));
    private final Setting.Num fillRange = add(new Setting.Num("Fill range (chunks)", 6, 1, 16, 1));
    private final Setting.Bool fade = add(new Setting.Bool("Fade with distance", true));
    private final Setting.Bool followY = add(new Setting.Bool("Follow my height", true));
    private final Setting.Num height = add(new Setting.Num("Fixed height", 64, -64, 320, 1));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.75f).wheel());

    private final Set<Long> chunks = new HashSet<>();
    /** When each chunk was first detected this session (ms). Chunks loaded from the save file have no entry. */
    private final java.util.Map<Long, Long> times = new java.util.HashMap<>();
    private String lastKey = "";
    private boolean dirty;
    private int saveTimer;
    private final double[] seg = new double[4];
    private final double[] wx = new double[4], wy = new double[4], wz = new double[4];
    private final double[] sx = new double[16], sy = new double[16];

    public NewChunks() {
        super("New Chunks", Category.BASE);
        instance = this;
        add(new Setting.Button("Clear this world", this::clear));
    }

    public static NewChunks get() { return instance; }

    /** Every detected new chunk (packed ChunkPos) of the current world; read only, copy before keeping. */
    public Set<Long> chunkSet() { return chunks; }
    /** Detection time per chunk (ms), only for chunks found this session. */
    public java.util.Map<Long, Long> chunkTimes() { return times; }

    /** Called by the network mixin for every block update the server sends. */
    public static void onBlockUpdate(BlockPos pos, BlockState state) {
        if (instance == null || !instance.isEnabled()) return;
        FluidState fs = state.getFluidState();
        if (fs.isEmpty() || fs.isSource()) return;                  // only flowing (non-source) fluid
        if (!instance.syncWorld()) return;                         // make sure we are filing it under the right world
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (instance.chunks.add(key)) { instance.dirty = true; instance.times.put(key, System.currentTimeMillis()); }
    }

    private String worldKey() {
        String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
        String dim = mc.level != null ? mc.level.dimension().identifier().toString() : "?";
        return (server + "_" + dim).replaceAll("[^A-Za-z0-9._\\-]", "_");
    }

    private Path file(String key) {
        return FabricLoader.getInstance().getConfigDir().resolve("kuro").resolve("newchunks").resolve(key + ".txt");
    }

    /**
     * Switches to the saved set of the current server + dimension if it changed. Block updates call this too:
     * after a dimension change the first updates of the new world can arrive before the next tick, and they must
     * not end up in (and be saved into) the old world's set.
     */
    private boolean syncWorld() {
        if (mc.level == null || mc.player == null) return false;
        String k = worldKey();
        if (!k.equals(lastKey)) {
            if (!lastKey.isEmpty()) save(lastKey);
            lastKey = k;
            chunks.clear();
            times.clear();
            dirty = false;
            saveTimer = 0;
            load(k);
        }
        return true;
    }

    public void clear() {
        chunks.clear();
        times.clear();
        dirty = true;
        save(lastKey);
        CommandManager.say("New chunks cleared for this world.");
    }

    @Override
    protected void onDisable() { save(lastKey); }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) {               // left the world: don't lose what we found
            if (dirty) save(lastKey);
            return;
        }
        syncWorld();
        if (dirty && ++saveTimer >= 600) { saveTimer = 0; save(lastKey); }   // write at most every 30 seconds
    }

    private void load(String key) {
        try {
            Path f = file(key);
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f)) {
                String[] p = line.trim().split(" ");
                if (p.length < 2) continue;
                try { chunks.add(ChunkPos.asLong(Integer.parseInt(p[0]), Integer.parseInt(p[1]))); }
                catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private void save(String key) {
        if (key == null || key.isEmpty() || !dirty) return;
        try {
            List<String> lines = new ArrayList<>(chunks.size());
            for (long c : chunks) lines.add(ChunkPos.getX(c) + " " + ChunkPos.getZ(c));
            Files.createDirectories(file(key).getParent());
            Files.write(file(key), lines);
            dirty = false;
        } catch (IOException ignored) {}
    }

    // ---------------- drawing ----------------

    private static final int MAX_FILLED = 48;      // fill budget per frame (every filled row is a GPU rectangle)
    private int filled;

    @Override
    public void onHud(GuiGraphics g) {
        if (chunks.isEmpty() || mc.player == null) return;
        int rad = range.intValue(), fillRad = Math.min(rad, fillRange.intValue());
        ChunkPos pc = mc.player.chunkPosition();
        double y = followY.value ? Math.floor(mc.player.getY()) : height.value;
        int rgb = color.rgb();
        filled = 0;

        // nearest rings first, so the fill budget is spent on the chunks around you
        for (int ring = 0; ring <= rad; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                int step = Math.abs(dx) == ring ? 1 : Math.max(1, ring * 2);
                for (int dz = -ring; dz <= ring; dz += step) {
                    int cx = pc.x + dx, cz = pc.z + dz;
                    if (!chunks.contains(ChunkPos.asLong(cx, cz))) continue;
                    double f = fade.value ? 1.0 - 0.6 * ring / Math.max(1, rad) : 1.0;
                    drawChunk(g, cx, cz, y, rgb, f, ring <= fillRad);
                }
            }
        }
    }

    private void drawChunk(GuiGraphics g, int cx, int cz, double y, int rgb, double f, boolean allowFill) {
        double x0 = cx * 16.0, z0 = cz * 16.0, x1 = x0 + 16, z1 = z0 + 16;
        wx[0] = x0; wz[0] = z0; wx[1] = x1; wz[1] = z0; wx[2] = x1; wz[2] = z1; wx[3] = x0; wz[3] = z1;
        for (int i = 0; i < 4; i++) wy[i] = y;

        if (fill.value && allowFill && filled < MAX_FILLED) {
            int n = Projector.polygon(wx, wy, wz, 4, sx, sy);     // clipped at the near plane: no popping underfoot
            if (n >= 3) {
                Gfx.fillPolygon(g, sx, sy, n, Look.withAlpha(rgb, (int) (fillOpacity.value * f)));
                filled++;
            }
        }
        if (outline.value) {
            int line = Look.withAlpha(rgb, (int) (255 * f));
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) & 3;
                if (Projector.segment(wx[i], y, wz[i], wx[j], y, wz[j], seg))
                    Gfx.line(g, seg[0], seg[1], seg[2], seg[3], 1, line);
            }
        }
    }
}
