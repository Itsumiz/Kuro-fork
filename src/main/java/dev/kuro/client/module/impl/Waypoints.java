package dev.kuro.client.module.impl;

import dev.kuro.client.gui.WaypointScreen;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved coordinates, drawn on screen with their distance. Per server and dimension, stored in
 * config/kuro-waypoints.txt. Add from the dropdown ("Add here"), or bind a key to the "Add Waypoint"
 * module. "Death point" saves where you died. "Manage waypoints..." opens a screen where every waypoint of
 * this world can be renamed, recoloured or deleted, and new ones can be added with a name.
 */
public class Waypoints extends Module {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kuro-waypoints.txt");
    private static Waypoints instance;

    /** One saved waypoint. hue below 0 means the default colour (white). */
    public static final class Wp {
        public final String key;
        public String name;
        public final int x, y, z;
        public float hue;

        Wp(String key, String name, int x, int y, int z, float hue) {
            this.key = key; this.name = name; this.x = x; this.y = y; this.z = z; this.hue = hue;
        }

        /** The colour as 0xRRGGBB. */
        public int rgb() { return hue < 0 ? 0xFFFFFF : Mth.hsvToRgb(hue, 1f, 1f) & 0xFFFFFF; }
    }

    private final List<Wp> all = new ArrayList<>();
    private final Setting.Bool showDistance = add(new Setting.Bool("Show distance", true));
    private final Setting.Bool deathPoint = add(new Setting.Bool("Death point", true));
    private final Setting.Num maxDistance = add(new Setting.Num("Max distance (0=any)", 0, 0, 5000, 100));
    private final double[] o = new double[2];
    private BlockPos lastAlive;
    private boolean deathSaved;

    public Waypoints() {
        super("Waypoints", Category.RENDER);
        instance = this;
        add(new Setting.Button("Manage waypoints...", () -> mc.setScreen(new WaypointScreen(mc.screen, this))));
        add(new Setting.Button("Add here", this::addHere));
        add(new Setting.Button("Remove nearest", this::removeNearest));
        add(new Setting.Button("List in chat", this::list));
        add(new Setting.Button("Clear all (this world)", this::clearHere));
        load();
    }

    public static Waypoints get() { return instance; }

    private String key() {
        String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
        String dim = mc.level != null ? mc.level.dimension().identifier().toString() : "?";
        return server + "|" + dim;
    }

    private void say(String s) { if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Waypoints] " + s), false); }

    /** The waypoints of the current server and dimension, in the order they were added. */
    public List<Wp> here() {
        String k = key();
        List<Wp> out = new ArrayList<>();
        for (Wp w : all) if (w.key.equals(k)) out.add(w);
        return out;
    }

    public void addHere() {
        if (mc.player == null || mc.level == null) return;
        add(mc.player.blockPosition(), null, -1f);
    }

    /** Adds a waypoint where you stand with the given name (null or blank = automatic name). */
    public Wp addNamed(String name) {
        if (mc.player == null || mc.level == null) return null;
        return add(mc.player.blockPosition(), name, -1f);
    }

    /** Adds a waypoint at any position (used by the Found List screen). */
    public Wp addAt(BlockPos p, String name) { return add(p, name, -1f); }

    private Wp add(BlockPos p, String name, float hue) {
        String k = key();
        name = clean(name);
        if (name.isEmpty()) {
            int n = 1;
            for (Wp w : all) if (w.key.equals(k)) n++;
            name = "WP " + n;
        }
        Wp w = new Wp(k, name, p.getX(), p.getY(), p.getZ(), hue);
        all.add(w);
        save();
        say("Saved " + name + " at " + p.getX() + " " + p.getY() + " " + p.getZ());
        return w;
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    /** Renames a waypoint (blank names are ignored) and saves. */
    public void rename(Wp w, String name) {
        name = clean(name);
        if (!name.isEmpty()) w.name = name;
        save();
    }

    public void remove(Wp w) {
        all.remove(w);
        save();
    }

    private void removeNearest() {
        if (mc.player == null) return;
        Wp best = null;
        double bd = Double.MAX_VALUE;
        for (Wp w : here()) {
            double d = mc.player.distanceToSqr(w.x + 0.5, w.y, w.z + 0.5);
            if (d < bd) { bd = d; best = w; }
        }
        if (best == null) { say("No waypoints here."); return; }
        all.remove(best);
        save();
        say("Removed " + best.name);
    }

    private void list() {
        int n = 0;
        for (Wp w : here()) {
            say(w.name + ": " + w.x + " " + w.y + " " + w.z);
            n++;
        }
        if (n == 0) say("No waypoints here.");
    }

    private void clearHere() {
        String k = key();
        all.removeIf(w -> w.key.equals(k));
        save();
        say("Cleared waypoints for this world.");
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        if (mc.screen instanceof DeathScreen) {
            if (deathPoint.value && !deathSaved && lastAlive != null) {
                deathSaved = true;
                add(lastAlive, "Death", 0f);          // death points start out red
            }
        } else {
            deathSaved = false;
            if (mc.player.isAlive()) lastAlive = mc.player.blockPosition();
        }
    }

    private static int lighten(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        r += (255 - r) * 35 / 100; g += (255 - g) * 35 / 100; b += (255 - b) * 35 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    public void onHud(GuiGraphics g) {
        var tr = mc.font;
        double maxD = maxDistance.value;
        for (Wp w : here()) {
            double dx = w.x + 0.5 - Projector.camPos.x, dy = w.y + 1.0 - Projector.camPos.y, dz = w.z + 0.5 - Projector.camPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (maxD > 0 && dist > maxD) continue;
            if (!Projector.project(w.x + 0.5, w.y + 1.0, w.z + 0.5, o)) continue;
            if (o[0] < 0 || o[1] < 0 || o[0] > Projector.width() || o[1] > Projector.height()) continue;
            int x = (int) o[0], y = (int) o[1];
            int col = 0xFF000000 | w.rgb();
            String text = showDistance.value ? w.name + " " + Math.round(dist) + "m" : w.name;
            g.fill(x - 4, y - 1, x + 5, y + 2, 0xFF000000);                        // diamond marker with a dark rim
            g.fill(x - 1, y - 4, x + 2, y + 5, 0xFF000000);
            g.fill(x - 3, y, x + 4, y + 1, col);
            g.fill(x, y - 3, x + 1, y + 4, col);
            g.fill(x - 2, y - 1, x + 3, y + 2, col);
            Look.tag(g, tr, text, x, y - 20, col);
        }
    }

    // file format: key <tab> name <tab> x <tab> y <tab> z [<tab> hue]   (hue -1 = default white)
    private void load() {
        try {
            if (!Files.exists(FILE)) return;
            for (String line : Files.readAllLines(FILE)) {
                String[] p = line.split("\t");
                if (p.length < 5) continue;
                try {
                    float hue = -1f;
                    if (p.length > 5) {
                        try { hue = Float.parseFloat(p[5]); } catch (NumberFormatException ignored) {}
                    }
                    all.add(new Wp(p[0], p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]), hue));
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    public void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (Wp w : all) {
                lines.add(w.key + "\t" + w.name.replace('\t', ' ') + "\t" + w.x + "\t" + w.y + "\t" + w.z + "\t" + w.hue);
            }
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, lines);
        } catch (IOException ignored) {}
    }
}
