package dev.kuro.client.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * WHERE every block of a schematic goes: a grid of palette ids plus the Block each id means (null = air / unknown / ignored).
 * Used by Auto Build to compare the schematic with the world (progress, next blocks). Reads Sponge .schem (v2, v3) and
 * Litematica .litematic. Block properties (facing, half...) are ignored: a block counts as done when the right block type is there.
 * Index of a position is (y * sizeZ + z) * sizeX + x, relative to the schematic's minimum corner.
 */
public final class SchematicLayout {
    private static final long MAX_VOLUME = 64L * 1024 * 1024;

    public final int sx, sy, sz;
    public final int[] ids;
    public final Block[] blocks;       // by id; null = not tracked
    public final int targets;          // positions that hold a tracked block

    private SchematicLayout(int sx, int sy, int sz, int[] ids, Block[] blocks) {
        this.sx = sx; this.sy = sy; this.sz = sz; this.ids = ids; this.blocks = blocks;
        int n = 0;
        for (int id : ids) if (blocks[id] != null) n++;
        this.targets = n;
    }

    public int index(int x, int y, int z) { return (y * sz + z) * sx + x; }
    public Block blockAt(int index) { return blocks[ids[index]]; }

    // ------------------------------------------------------------------ loading

    public static SchematicLayout load(Path file) throws IOException {
        Map<String, Object> root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readUnsignedByte() != 10) throw new IOException("not an NBT file");
            in.readUTF();
            root = SchematicInfo.readCompound(in);
        }
        List<String> names = new ArrayList<>();
        Map<String, Integer> nameIds = new HashMap<>();
        names.add("minecraft:air");
        nameIds.put("minecraft:air", 0);

        if (root.get("Regions") instanceof Map<?, ?> regions) return fromLitematic(regions, names, nameIds);
        Map<?, ?> s = root.get("Schematic") instanceof Map<?, ?> m ? m : root;
        return fromSponge(s, names, nameIds);
    }

    private static int idFor(String blockState, List<String> names, Map<String, Integer> nameIds) {
        int bracket = blockState.indexOf('[');
        String name = bracket >= 0 ? blockState.substring(0, bracket) : blockState;
        Integer id = nameIds.get(name);
        if (id == null) { id = names.size(); names.add(name); nameIds.put(name, id); }
        return id;
    }

    private static Block[] resolve(List<String> names) {
        Block[] out = new Block[names.size()];
        for (int i = 0; i < out.length; i++) {
            Identifier id = Identifier.tryParse(names.get(i));
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
            var opt = BuiltInRegistries.BLOCK.get(id);
            if (opt.isEmpty()) continue;
            Block b = opt.get().value();
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR || b == Blocks.STRUCTURE_VOID) continue;
            out[i] = b;
        }
        return out;
    }

    private static SchematicLayout fromSponge(Map<?, ?> s, List<String> names, Map<String, Integer> nameIds) throws IOException {
        Map<?, ?> palette;
        byte[] data;
        if (s.get("Palette") instanceof Map<?, ?> p && s.get("BlockData") instanceof byte[] d) { palette = p; data = d; }
        else if (s.get("Blocks") instanceof Map<?, ?> b && b.get("Palette") instanceof Map<?, ?> p && b.get("Data") instanceof byte[] d) { palette = p; data = d; }
        else throw new IOException("only .schem (Sponge) and .litematic can be shown");
        int w = (int) num(s.get("Width")), h = (int) num(s.get("Height")), l = (int) num(s.get("Length"));
        if (w <= 0 || h <= 0 || l <= 0 || (long) w * h * l > MAX_VOLUME) throw new IOException("schematic size not supported");

        Map<Integer, Integer> local = new HashMap<>();          // file palette index -> our id
        for (Map.Entry<?, ?> e : palette.entrySet()) {
            if (e.getValue() instanceof Number n) local.put(n.intValue(), idFor(String.valueOf(e.getKey()), names, nameIds));
        }
        int[] ids = new int[w * h * l];
        int i = 0, o = 0;
        while (i < data.length && o < ids.length) {
            int value = 0, shift = 0, b;
            do { b = data[i++]; value |= (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0 && i < data.length);
            ids[o++] = local.getOrDefault(value, 0);
        }
        return new SchematicLayout(w, h, l, ids, resolve(names));
    }

    private static SchematicLayout fromLitematic(Map<?, ?> regions, List<String> names, Map<String, Integer> nameIds) throws IOException {
        // pass 1: bounds of all regions
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Object r : regions.values()) {
            if (!(r instanceof Map<?, ?> region) || !(region.get("Size") instanceof Map<?, ?> size) || !(region.get("Position") instanceof Map<?, ?> pos)) continue;
            int[] lo = regionMin(pos, size), ext = {(int) Math.abs(num(size.get("x"))), (int) Math.abs(num(size.get("y"))), (int) Math.abs(num(size.get("z")))};
            minX = Math.min(minX, lo[0]); minY = Math.min(minY, lo[1]); minZ = Math.min(minZ, lo[2]);
            maxX = Math.max(maxX, lo[0] + ext[0]); maxY = Math.max(maxY, lo[1] + ext[1]); maxZ = Math.max(maxZ, lo[2] + ext[2]);
        }
        if (minX == Integer.MAX_VALUE) throw new IOException("empty litematic");
        int w = maxX - minX, h = maxY - minY, l = maxZ - minZ;
        if (w <= 0 || h <= 0 || l <= 0 || (long) w * h * l > MAX_VOLUME) throw new IOException("schematic size not supported");
        int[] ids = new int[w * h * l];

        for (Object r : regions.values()) {
            if (!(r instanceof Map<?, ?> region) || !(region.get("Size") instanceof Map<?, ?> size) || !(region.get("Position") instanceof Map<?, ?> pos)) continue;
            if (!(region.get("BlockStatePalette") instanceof List<?> palette) || palette.isEmpty()) continue;
            if (!(region.get("BlockStates") instanceof long[] states)) continue;
            int rx = (int) Math.abs(num(size.get("x"))), ry = (int) Math.abs(num(size.get("y"))), rz = (int) Math.abs(num(size.get("z")));
            int[] lo = regionMin(pos, size);
            int[] map = new int[palette.size()];
            for (int i = 0; i < map.length; i++) {
                String n = palette.get(i) instanceof Map<?, ?> e && e.get("Name") instanceof String str ? str : "minecraft:air";
                map[i] = idFor(n, names, nameIds);
            }
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(map.length - 1));
            long mask = (1L << bits) - 1L;
            long volume = (long) rx * ry * rz;
            for (long index = 0; index < volume; index++) {
                long startBit = index * bits;
                int startWord = (int) (startBit >>> 6), endWord = (int) (((index + 1) * bits - 1) >>> 6), offset = (int) (startBit & 63L);
                if (endWord >= states.length) break;
                long v = startWord == endWord ? (states[startWord] >>> offset) & mask
                        : ((states[startWord] >>> offset) | (states[endWord] << (64 - offset))) & mask;
                if (v >= map.length) continue;
                int x = (int) (index % rx), z = (int) ((index / rx) % rz), y = (int) (index / ((long) rx * rz));
                int gx = lo[0] - minX + x, gy = lo[1] - minY + y, gz = lo[2] - minZ + z;
                ids[(gy * l + gz) * w + gx] = map[(int) v];
            }
        }
        return new SchematicLayout(w, h, l, ids, resolve(names));
    }

    /** Smallest corner of a region: Position, moved back when a size is negative. */
    private static int[] regionMin(Map<?, ?> pos, Map<?, ?> size) {
        long sx = num(size.get("x")), sy = num(size.get("y")), sz = num(size.get("z"));
        return new int[]{(int) (num(pos.get("x")) + Math.min(0, sx + 1)), (int) (num(pos.get("y")) + Math.min(0, sy + 1)), (int) (num(pos.get("z")) + Math.min(0, sz + 1))};
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
