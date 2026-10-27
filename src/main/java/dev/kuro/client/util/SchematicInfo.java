package dev.kuro.client.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads WHAT a schematic needs (which items, how many of each) so the builder knows what to take from the refill chest.
 * Supports Sponge .schem (v2 and v3) and Litematica .litematic. Legacy MCEdit .schematic is not read (Baritone still builds it,
 * the refill then takes every placeable block instead of only the needed ones).
 *
 * Uses its own small NBT reader so it does not depend on any Minecraft NBT API names.
 */
public final class SchematicInfo {
    /** needed items, most needed first */
    public final Map<Item, Integer> items;
    public final int totalBlocks;
    public final String fileName;

    private SchematicInfo(String fileName, Map<Item, Integer> items, int totalBlocks) {
        this.fileName = fileName;
        this.items = items;
        this.totalBlocks = totalBlocks;
    }

    /** Parses the file. Throws IOException with a readable message when the format is not supported. */
    public static SchematicInfo load(Path file) throws IOException {
        Map<String, Integer> byBlock = new HashMap<>();
        Map<String, Object> root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readUnsignedByte() != 10) throw new IOException("not an NBT file");
            in.readUTF();
            root = readCompound(in);
        }

        if (root.get("Regions") instanceof Map<?, ?> regions) {
            for (Object r : regions.values()) if (r instanceof Map<?, ?> region) readLitematicRegion(region, byBlock);
        } else {
            Map<?, ?> s = root.get("Schematic") instanceof Map<?, ?> m ? m : root;
            if (!readSponge(s, byBlock)) {
                throw new IOException("only .schem (Sponge) and .litematic can be read for materials");
            }
        }

        Map<Item, Integer> byItem = new HashMap<>();
        int total = 0;
        for (Map.Entry<String, Integer> e : byBlock.entrySet()) {
            String st = e.getKey();
            if (st.contains("half=upper") || st.contains("part=head")) continue;   // 2nd half of a door / tall plant / bed: same item
            Item item = itemFor(st);
            if (item == null) continue;
            int n = e.getValue() * (st.contains("type=double") && st.contains("_slab") ? 2 : 1);   // a double slab is 2 items
            byItem.merge(item, n, Integer::sum);
            total += n;
        }
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(byItem.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<Item, Integer> ordered = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : sorted) ordered.put(e.getKey(), e.getValue());
        return new SchematicInfo(file.getFileName().toString(), ordered, total);
    }

    /** "minecraft:oak_stairs[facing=north]" -> the item that places it, or null (air, water, fire...). */
    private static Item itemFor(String blockState) {
        int bracket = blockState.indexOf('[');
        String name = bracket >= 0 ? blockState.substring(0, bracket) : blockState;
        Identifier id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        var opt = BuiltInRegistries.BLOCK.get(id);
        if (opt.isEmpty()) return null;
        Block b = opt.get().value();
        Item item = b.asItem();
        return item == Items.AIR ? null : item;
    }

    // ------------------------------------------------------------------ Sponge .schem

    private static boolean readSponge(Map<?, ?> s, Map<String, Integer> out) {
        Map<?, ?> palette;
        byte[] data;
        if (s.get("Palette") instanceof Map<?, ?> p && s.get("BlockData") instanceof byte[] d) {          // v2
            palette = p; data = d;
        } else if (s.get("Blocks") instanceof Map<?, ?> b                                                    // v3
                && b.get("Palette") instanceof Map<?, ?> p && b.get("Data") instanceof byte[] d) {
            palette = p; data = d;
        } else {
            return false;
        }
        Map<Integer, String> names = new HashMap<>();
        for (Map.Entry<?, ?> e : palette.entrySet()) {
            if (e.getValue() instanceof Number n) names.put(n.intValue(), String.valueOf(e.getKey()));
        }
        int i = 0;
        while (i < data.length) {                            // BlockData is a stream of varints, one per block
            int value = 0, shift = 0, b;
            do {
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);
            String name = names.get(value);
            if (name != null) out.merge(name, 1, Integer::sum);
        }
        return true;
    }

    // ------------------------------------------------------------------ Litematica .litematic

    private static void readLitematicRegion(Map<?, ?> region, Map<String, Integer> out) {
        if (!(region.get("BlockStatePalette") instanceof List<?> palette) || palette.isEmpty()) return;
        if (!(region.get("BlockStates") instanceof long[] states)) return;
        if (!(region.get("Size") instanceof Map<?, ?> size)) return;
        long volume = Math.abs(num(size.get("x"))) * Math.abs(num(size.get("y"))) * Math.abs(num(size.get("z")));
        String[] names = new String[palette.size()];
        for (int i = 0; i < names.length; i++) {
            if (palette.get(i) instanceof Map<?, ?> entry && entry.get("Name") instanceof String n) names[i] = n;
        }
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(names.length - 1));
        long mask = (1L << bits) - 1L;
        int[] counts = new int[names.length];
        for (long index = 0; index < volume; index++) {
            long startBit = index * bits;
            int startWord = (int) (startBit >>> 6);
            int endWord = (int) (((index + 1) * bits - 1) >>> 6);
            int offset = (int) (startBit & 63L);
            if (endWord >= states.length) break;
            long v = startWord == endWord
                    ? (states[startWord] >>> offset) & mask
                    : ((states[startWord] >>> offset) | (states[endWord] << (64 - offset))) & mask;
            if (v < counts.length) counts[(int) v]++;
        }
        for (int i = 0; i < names.length; i++) if (names[i] != null && counts[i] > 0) out.merge(names[i], counts[i], Integer::sum);
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    // ------------------------------------------------------------------ minimal NBT reader

    private static Object readTag(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] a = new byte[in.readInt()]; in.readFully(a); return a; }
            case 8: return in.readUTF();
            case 9: {
                int elementType = in.readUnsignedByte(), n = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, Math.min(n, 1 << 16)));
                for (int i = 0; i < n; i++) list.add(readTag(in, elementType));
                return list;
            }
            case 10: return readCompound(in);
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("bad NBT tag " + type);
        }
    }

    static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> map = new HashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return map;
            String name = in.readUTF();
            map.put(name, readTag(in, type));
        }
    }
}
