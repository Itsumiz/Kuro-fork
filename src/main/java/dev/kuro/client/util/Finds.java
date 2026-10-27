package dev.kuro.client.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared list of everything the base-finding modules have found; shown by the Found List screen. */
public final class Finds {
    private Finds() {}

    /** score is 0..100 (percent for bases, grown clusters for geodes, block count for stashes...). */
    public record Find(String source, String label, int x, int y, int z, double score) {}

    private static final Map<String, List<Find>> BY_SOURCE = new LinkedHashMap<>();

    /** Replaces everything one module has published. Pass an empty list to clear it. */
    public static synchronized void set(String source, List<Find> list) {
        if (list.isEmpty()) BY_SOURCE.remove(source); else BY_SOURCE.put(source, new ArrayList<>(list));
    }

    public static synchronized List<Find> all() {
        List<Find> out = new ArrayList<>();
        for (List<Find> l : BY_SOURCE.values()) out.addAll(l);
        return out;
    }

    public static synchronized List<String> sources() { return new ArrayList<>(BY_SOURCE.keySet()); }
}
