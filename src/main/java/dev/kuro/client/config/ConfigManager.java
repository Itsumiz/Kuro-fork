package dev.kuro.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kuro.client.KuroClient;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import dev.kuro.client.module.impl.BlockEsp;
import dev.kuro.client.util.Friends;
import com.google.gson.JsonArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Saves module on/off states, keybinds and every setting.
 * "default.json" is loaded when you first join a world and rewritten automatically whenever something changes.
 * Named profiles live in config/kuro/profiles/ (use .config save/load/list/delete).
 */
public final class ConfigManager {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("kuro");
    private static final Path DEFAULT = DIR.resolve("default.json");
    private static final Path PROFILES = DIR.resolve("profiles");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Modules that are never switched on again when a config loads (they only make sense on demand). */
    private static final Set<String> NO_RESTORE = Set.of("auto fish", "radial menu", "freecam", "toggle all", "add waypoint", "macro record", "auto walk");

    private static String lastSaved = "";
    private static boolean started;
    private static int timer;

    private ConfigManager() {}

    public static void init() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { if (started) write(DEFAULT, toJson()); } catch (Exception ignored) {}
        }));
    }

    /** Call every client tick. */
    public static void tick() {
        if (!started) {
            if (mc.level == null || mc.player == null) return;
            started = true;
            try { if (Files.exists(DEFAULT)) apply(read(DEFAULT)); } catch (Exception ignored) {}
            lastSaved = toJson();
            return;
        }
        if (++timer < 100) return;                       // check for changes every 5 seconds
        timer = 0;
        String now = toJson();
        if (!now.equals(lastSaved)) {
            lastSaved = now;
            try { write(DEFAULT, now); } catch (IOException ignored) {}
        }
    }

    // ---------------- profiles ----------------

    public static String clean(String name) { return name.replaceAll("[^A-Za-z0-9_\\-]", ""); }

    public static boolean save(String name) {
        name = clean(name);
        if (name.isEmpty()) return false;
        try { write(PROFILES.resolve(name + ".json"), toJson()); return true; } catch (IOException e) { return false; }
    }

    public static boolean load(String name) {
        Path p = PROFILES.resolve(clean(name) + ".json");
        if (!Files.exists(p)) return false;
        try { apply(read(p)); return true; } catch (Exception e) { return false; }
    }

    public static boolean delete(String name) {
        try { return Files.deleteIfExists(PROFILES.resolve(clean(name) + ".json")); } catch (IOException e) { return false; }
    }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(PROFILES)) return out;
        try (Stream<Path> s = Files.list(PROFILES)) {
            s.forEach(p -> {
                String f = p.getFileName().toString();
                if (f.endsWith(".json")) out.add(f.substring(0, f.length() - 5));
            });
        } catch (IOException ignored) {}
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    // ---------------- config slots (the sidebar's Configs page): exactly SLOTS saved configs ----------------

    public static final int SLOTS = 6;

    /** What the GUI shows for one slot. */
    public record SlotInfo(boolean used, String name, long savedAt) {}
    private static final SlotInfo EMPTY = new SlotInfo(false, "", 0L);
    private static final SlotInfo[] slotCache = new SlotInfo[SLOTS];
    private static int activeSlot = -1;   // the slot last saved to / loaded from (this session only)

    private static Path slotPath(int slot) { return PROFILES.resolve("slot" + (slot + 1) + ".json"); }   // same files the Config module's "Slot" uses
    private static boolean validSlot(int slot) { return slot >= 0 && slot < SLOTS; }
    public static String defaultSlotName(int slot) { return "Config " + (slot + 1); }
    public static int activeSlot() { return activeSlot; }

    /** Cached (the GUI asks every frame); refreshed by every change made here or by refreshSlots(). */
    public static SlotInfo slot(int slot) {
        if (!validSlot(slot)) return EMPTY;
        SlotInfo c = slotCache[slot];
        if (c == null) c = slotCache[slot] = readSlotInfo(slot);
        return c;
    }

    public static void refreshSlots() { java.util.Arrays.fill(slotCache, null); }

    private static SlotInfo readSlotInfo(int slot) {
        Path p = slotPath(slot);
        if (!Files.exists(p)) return EMPTY;
        String name = defaultSlotName(slot);
        long at = 0L;
        try {
            JsonObject root = read(p);
            if (root.has("meta") && root.get("meta").isJsonObject()) {
                JsonObject meta = root.getAsJsonObject("meta");
                if (meta.has("name")) name = meta.get("name").getAsString();
                if (meta.has("saved")) at = meta.get("saved").getAsLong();
            }
            if (at == 0L) at = Files.getLastModifiedTime(p).toMillis();
        } catch (Exception ignored) {}
        return new SlotInfo(true, name, at);
    }

    /** Saves everything into the slot. A blank name keeps the slot's current name (or "Config N" for a new one). */
    public static boolean saveSlot(int slot, String name) {
        if (!validSlot(slot)) return false;
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) { SlotInfo old = slot(slot); n = old.used() ? old.name() : defaultSlotName(slot); }
        if (n.length() > 24) n = n.substring(0, 24);
        try {
            JsonObject root = JsonParser.parseString(toJson()).getAsJsonObject();
            JsonObject meta = new JsonObject();
            meta.addProperty("name", n);
            meta.addProperty("saved", System.currentTimeMillis());
            root.add("meta", meta);
            write(slotPath(slot), GSON.toJson(root));
            slotCache[slot] = null;
            activeSlot = slot;
            return true;
        } catch (Exception e) { return false; }
    }

    public static boolean loadSlot(int slot) {
        if (!validSlot(slot) || !Files.exists(slotPath(slot))) return false;
        try { apply(read(slotPath(slot))); activeSlot = slot; return true; } catch (Exception e) { return false; }
    }

    public static boolean deleteSlot(int slot) {
        if (!validSlot(slot)) return false;
        try {
            boolean gone = Files.deleteIfExists(slotPath(slot));
            slotCache[slot] = null;
            if (activeSlot == slot) activeSlot = -1;
            return gone;
        } catch (IOException e) { return false; }
    }

    /** Renames a saved slot without touching its settings. */
    public static boolean renameSlot(int slot, String name) {
        if (!validSlot(slot) || !Files.exists(slotPath(slot))) return false;
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return false;
        if (n.length() > 24) n = n.substring(0, 24);
        try {
            JsonObject root = read(slotPath(slot));
            JsonObject meta = root.has("meta") && root.get("meta").isJsonObject() ? root.getAsJsonObject("meta") : new JsonObject();
            meta.addProperty("name", n);
            if (!meta.has("saved")) meta.addProperty("saved", Files.getLastModifiedTime(slotPath(slot)).toMillis());
            root.add("meta", meta);
            write(slotPath(slot), GSON.toJson(root));
            slotCache[slot] = null;
            return true;
        } catch (Exception e) { return false; }
    }

    // ---------------- (de)serialising ----------------

    private static JsonObject read(Path p) throws IOException {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    private static void write(Path p, String json) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, json);
    }

    // ---------------- import / export (one copy-pasteable string) ----------------

    private static final String PREFIX = "kuro1:";

    /** Everything (modules, keybinds, settings, Block ESP list, optionally friends) as one text string. */
    public static String exportString(boolean withFriends) {
        try {
            JsonObject root = JsonParser.parseString(toJson()).getAsJsonObject();
            for (Module m : KuroClient.modules.modules) {
                if (m instanceof BlockEsp be) {
                    JsonArray arr = new JsonArray();
                    for (var b : be.targets) {
                        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString();
                        Float h = be.hues.get(b);
                        arr.add(h == null ? id : id + " " + h);
                    }
                    root.add("blocks", arr);
                }
            }
            if (withFriends) {
                JsonArray f = new JsonArray();
                for (String n : Friends.list()) f.add(n);
                root.add("friends", f);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
            return PREFIX + Base64.getEncoder().withoutPadding().encodeToString(bo.toByteArray());
        } catch (IOException e) { return ""; }
    }

    /** Applies an exported string. Returns null on success or a short error message. */
    public static String importString(String text) {
        if (text == null) return "Nothing to import";
        text = text.trim();
        if (!text.startsWith(PREFIX)) return "That isn't an Kuro config string";
        try {
            byte[] raw = Base64.getDecoder().decode(text.substring(PREFIX.length()));
            String json;
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                json = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            apply(root);
            if (root.has("blocks")) {
                for (Module m : KuroClient.modules.modules) {
                    if (!(m instanceof BlockEsp be)) continue;
                    be.targets.clear(); be.hues.clear();
                    for (JsonElement e : root.getAsJsonArray("blocks")) {
                        String[] parts = e.getAsString().trim().split("\\s+");
                        var id = net.minecraft.resources.Identifier.tryParse(parts[0]);
                        if (id == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)) continue;
                        var opt = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
                        if (opt.isEmpty()) continue;
                        var b = opt.get().value();
                        be.targets.add(b);
                        if (parts.length > 1) {
                            try { be.hues.put(b, Mth.clamp(Float.parseFloat(parts[1]), 0f, 0.999f)); } catch (NumberFormatException ignored) {}
                        }
                    }
                    be.changed();
                }
            }
            if (root.has("friends")) for (JsonElement e : root.getAsJsonArray("friends")) Friends.add(e.getAsString());
            return null;
        } catch (Exception e) {
            return "That config string is damaged or incomplete";
        }
    }

    private static String toJson() {
        JsonObject root = new JsonObject();
        JsonObject mods = new JsonObject();
        for (Module m : KuroClient.modules.modules) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", m.isEnabled());
            o.addProperty("key", m.key);
            JsonObject st = new JsonObject();
            for (Setting s : m.persisted()) {
                if (s instanceof Setting.Bool b) st.addProperty(s.name, b.value);
                else if (s instanceof Setting.Num n) st.addProperty(s.name, n.value);
                else if (s instanceof Setting.Hue h) {
                    st.addProperty(s.name, h.hue);
                    if (h.wheel) { st.addProperty(s.name + " sat", h.sat); st.addProperty(s.name + " val", h.val); }
                }
            }
            o.add("settings", st);
            mods.add(m.name, o);
        }
        root.add("modules", mods);
        return GSON.toJson(root);
    }

    private static void apply(JsonObject root) {
        dev.kuro.client.module.impl.Notifications.quiet(true);   // loading a config must not toast every module
        try { applyQuiet(root); } finally { dev.kuro.client.module.impl.Notifications.quiet(false); }
    }

    /** Older configs picked the Glass menu theme with two switches on Glass UI; those became presets of HUD > UI theme. */
    private static void migrateGlassThemes(JsonObject mods) {
        try {
            JsonObject hud = mods.has("HUD") ? mods.getAsJsonObject("HUD").getAsJsonObject("settings") : null;
            if (hud != null && hud.has("UI theme")) return;                         // already on the new system
            if (!mods.has("Glass UI") || !mods.getAsJsonObject("Glass UI").has("settings")) return;
            JsonObject g = mods.getAsJsonObject("Glass UI").getAsJsonObject("settings");
            String id = g.has("Astolfo theme") && g.get("Astolfo theme").getAsBoolean() ? "astolfo"
                    : g.has("Israel theme") && g.get("Israel theme").getAsBoolean() ? "israel" : null;
            if (id != null) dev.kuro.client.module.impl.Hud.setTheme(dev.kuro.client.gui.UiThemes.find(id));
        } catch (RuntimeException ignored) {}
    }

    private static void applyQuiet(JsonObject root) {
        if (!root.has("modules")) return;
        JsonObject mods = root.getAsJsonObject("modules");
        migrateGlassThemes(mods);
        for (Module m : KuroClient.modules.modules) {
            String key = mods.has(m.name) ? m.name : m.name.equals("Radial Menu") && mods.has("Circle") ? "Circle" : null;   // renamed modules
            if (key == null) continue;
            JsonObject o = mods.getAsJsonObject(key);
            try {
                if (o.has("key")) m.key = o.get("key").getAsInt();
                if (o.has("settings")) {
                    JsonObject st = o.getAsJsonObject("settings");
                    for (Setting s : m.persisted()) {
                        if (s instanceof Setting.Hue wh && wh.wheel) {   // wheel colours also keep saturation + brightness
                            if (st.has(s.name + " sat")) wh.sat = Mth.clamp(st.get(s.name + " sat").getAsFloat(), 0f, 1f);
                            if (st.has(s.name + " val")) wh.val = Mth.clamp(st.get(s.name + " val").getAsFloat(), 0f, 1f);
                        }
                        if (!st.has(s.name)) continue;
                        JsonElement e = st.get(s.name);
                        if (s instanceof Setting.Bool b) b.value = e.getAsBoolean();
                        else if (s instanceof Setting.Num n) n.value = Mth.clamp(e.getAsDouble(), n.min, n.max);
                        else if (s instanceof Setting.Hue h) h.hue = Mth.clamp(e.getAsFloat(), 0f, 0.999f);
                    }
                }
                if (o.has("enabled") && !NO_RESTORE.contains(m.name.toLowerCase(Locale.ROOT))) {
                    m.setEnabled(o.get("enabled").getAsBoolean());
                }
            } catch (RuntimeException ignored) {}
        }
    }
}
