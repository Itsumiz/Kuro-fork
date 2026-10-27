package dev.kuro.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The friends list. Friends are skipped by Kill Aura / Trigger Bot and shown in green by Hitboxes and Tracers. */
public final class Friends {
    public static final int DEFAULT_ARGB = 0xFF3DDC84;
    /** Set by the Friends module: its colour setting, and whether friends are currently protected. */
    public static java.util.function.IntSupplier colorSource = () -> DEFAULT_ARGB;
    public static java.util.function.BooleanSupplier protectSource = () -> true;
    public static int argb() { return colorSource.getAsInt(); }
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kuro-friends.txt");
    private static final Set<String> NAMES = new LinkedHashSet<>();   // stored lowercase

    static { load(); }

    private Friends() {}

    public static boolean is(String name) { return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT)); }

    /** True when the client must leave this entity alone (a friend while protection is on). Used by Kill Aura / Trigger Bot. */
    public static boolean shield(Entity e) { return protectSource.getAsBoolean() && is(e); }

    public static boolean is(Entity e) {
        return e instanceof Player p && is(p.getName().getString());
    }

    public static boolean add(String name) {
        boolean added = NAMES.add(name.toLowerCase(Locale.ROOT));
        if (added) save();
        return added;
    }

    public static boolean remove(String name) {
        boolean removed = NAMES.remove(name.toLowerCase(Locale.ROOT));
        if (removed) save();
        return removed;
    }

    public static int count() { return NAMES.size(); }
    public static void clear() { NAMES.clear(); save(); }
    public static List<String> list() { return new ArrayList<>(NAMES); }

    private static void load() {
        try {
            if (!Files.exists(FILE)) return;
            for (String line : Files.readAllLines(FILE)) {
                String n = line.trim();
                if (!n.isEmpty()) NAMES.add(n.toLowerCase(Locale.ROOT));
            }
        } catch (IOException ignored) {}
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, new ArrayList<>(NAMES));
        } catch (IOException ignored) {}
    }
}
