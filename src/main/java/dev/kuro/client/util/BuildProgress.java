package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares a schematic with the world a slice at a time (so it never causes a lag spike) and answers:
 * how much is done, and which blocks are most likely next. Block type only (not facing / half), unloaded chunks are skipped.
 */
public final class BuildProgress {
    private static final int BUDGET = 12000;           // positions checked per tick
    private static final byte UNKNOWN = 0, RIGHT = 1, WRONG = 2;

    public final SchematicLayout layout;
    private final BlockPos origin;
    private final byte[] status;
    private final int[] wrongPerLayer;
    private int right, wrong, cursor;
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    public BuildProgress(SchematicLayout layout, BlockPos origin) {
        this.layout = layout;
        this.origin = origin.immutable();
        this.status = new byte[layout.ids.length];
        this.wrongPerLayer = new int[layout.sy];
    }

    public BlockPos origin() { return origin; }
    public int total() { return layout.targets; }
    public int done() { return right; }
    public int missing() { return wrong; }
    public double fraction() { return layout.targets == 0 ? 1.0 : (double) right / layout.targets; }
    public int layerCount() { return layout.sy; }

    /** How many of each block type are still wrong/missing (loaded chunks only). */
    public java.util.Map<Block, Integer> missingBlocks() {
        java.util.Map<Block, Integer> out = new java.util.HashMap<>();
        for (int i = 0; i < status.length; i++) if (status[i] == WRONG) out.merge(layout.blocks[layout.ids[i]], 1, Integer::sum);
        return out;
    }

    /** The schematic's block for a world position, or null when outside it / not tracked. */
    public Block targetAt(BlockPos p) {
        int x = p.getX() - origin.getX(), y = p.getY() - origin.getY(), z = p.getZ() - origin.getZ();
        if (x < 0 || y < 0 || z < 0 || x >= layout.sx || y >= layout.sy || z >= layout.sz) return null;
        return layout.blockAt(layout.index(x, y, z));
    }

    /** Lowest layer (0-based) that still has a wrong block, or -1. */
    public int lowestWrongLayer() {
        for (int y = 0; y < wrongPerLayer.length; y++) if (wrongPerLayer[y] > 0) return y;
        return -1;
    }

    /** Checks the next slice of the schematic against the world. Call once per tick. */
    public void tick(Minecraft mc) {
        Level level = mc.level;
        if (level == null) return;
        int n = status.length;
        for (int k = 0; k < BUDGET; k++) {
            if (cursor >= n) cursor = 0;
            int i = cursor++;
            Block want = layout.blocks[layout.ids[i]];
            if (want == null) continue;
            int x = i % layout.sx, z = (i / layout.sx) % layout.sz, y = i / (layout.sx * layout.sz);
            probe.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            if (!level.hasChunkAt(probe)) continue;
            byte now = level.getBlockState(probe).getBlock() == want ? RIGHT : WRONG;
            byte old = status[i];
            if (now == old) continue;
            if (old == RIGHT) right--; else if (old == WRONG) { wrong--; wrongPerLayer[y]--; }
            if (now == RIGHT) right++; else { wrong++; wrongPerLayer[y]++; }
            status[i] = now;
        }
    }

    /**
     * Fills 'out' with the schematic indices of blocks that are still missing / wrong within 'radius' blocks of the player
     * (horizontally and vertically), at most out.length of them. Returns how many were written. Use blockPos / exposedFaces /
     * blockAtIndex to read an index back.
     */
    public int missingNear(int[] out, double px, double py, double pz, int radius) {
        int x0 = Math.max(0, (int) Math.floor(px) - origin.getX() - radius), x1 = Math.min(layout.sx - 1, (int) Math.floor(px) - origin.getX() + radius);
        int y0 = Math.max(0, (int) Math.floor(py) - origin.getY() - radius), y1 = Math.min(layout.sy - 1, (int) Math.floor(py) - origin.getY() + radius);
        int z0 = Math.max(0, (int) Math.floor(pz) - origin.getZ() - radius), z1 = Math.min(layout.sz - 1, (int) Math.floor(pz) - origin.getZ() + radius);
        int n = 0;
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = layout.index(x, y, z);
            if (status[i] != WRONG) continue;
            if (n >= out.length) return n;
            out[n++] = i;
        }
        return n;
    }

    public Block blockAtIndex(int i) { return layout.blockAt(i); }

    /** World position of a schematic index. */
    public BlockPos blockPos(int i) {
        return new BlockPos(origin.getX() + i % layout.sx, origin.getY() + i / (layout.sx * layout.sz), origin.getZ() + (i / layout.sx) % layout.sz);
    }

    /**
     * Which faces of the block at index i are not covered by another schematic block (bit 0 down, 1 up, 2 north -z, 3 south +z,
     * 4 west -x, 5 east +x). A face against another block of the schematic is hidden, which keeps the ghost light to draw.
     */
    public int exposedFaces(int i) {
        int x = i % layout.sx, z = (i / layout.sx) % layout.sz, y = i / (layout.sx * layout.sz);
        int m = 0;
        if (!solid(x, y - 1, z)) m |= 1;
        if (!solid(x, y + 1, z)) m |= 2;
        if (!solid(x, y, z - 1)) m |= 4;
        if (!solid(x, y, z + 1)) m |= 8;
        if (!solid(x - 1, y, z)) m |= 16;
        if (!solid(x + 1, y, z)) m |= 32;
        return m;
    }

    private boolean solid(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= layout.sx || y >= layout.sy || z >= layout.sz) return false;
        return layout.blockAt(layout.index(x, y, z)) != null;
    }

    /**
     * The blocks that are probably built next, nearest to the player first. With "build in layers" it is the lowest unfinished
     * layer (what Baritone does); otherwise the closest missing blocks around the player.
     */
    public List<BlockPos> next(int count, double px, double py, double pz, boolean layered) {
        List<BlockPos> out = new ArrayList<>();
        int low = lowestWrongLayer();
        if (low < 0) return out;
        int yFrom = low, yTo = low;
        int x0 = 0, x1 = layout.sx - 1, z0 = 0, z1 = layout.sz - 1;
        if (!layered) {
            yFrom = 0; yTo = layout.sy - 1;
            x0 = Math.max(0, (int) px - origin.getX() - 24); x1 = Math.min(layout.sx - 1, (int) px - origin.getX() + 24);
            z0 = Math.max(0, (int) pz - origin.getZ() - 24); z1 = Math.min(layout.sz - 1, (int) pz - origin.getZ() + 24);
        }
        double[] bestD = new double[count];
        int[] bestI = new int[count];
        int found = 0;
        for (int y = yFrom; y <= yTo; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
            int i = layout.index(x, y, z);
            if (status[i] != WRONG) continue;
            double dx = origin.getX() + x + 0.5 - px, dy = origin.getY() + y + 0.5 - py, dz = origin.getZ() + z + 0.5 - pz;
            double d = dx * dx + dy * dy + dz * dz;
            if (found == count && d >= bestD[count - 1]) continue;
            int pos = found < count ? found++ : count - 1;
            while (pos > 0 && bestD[pos - 1] > d) { bestD[pos] = bestD[pos - 1]; bestI[pos] = bestI[pos - 1]; pos--; }
            bestD[pos] = d; bestI[pos] = i;
        }
        for (int k = 0; k < found; k++) {
            int i = bestI[k];
            out.add(new BlockPos(origin.getX() + i % layout.sx, origin.getY() + i / (layout.sx * layout.sz), origin.getZ() + (i / layout.sx) % layout.sz));
        }
        return out;
    }
}
