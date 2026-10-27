package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Outlines dropped items through walls, with name, count and distance. Items whose id contains one of your "highlight" words
 * (diamond, netherite, totem ...) get their own colour. Stacks of the same item lying close together share one label, so a
 * pile of cobblestone does not bury the screen.
 */
public class ItemEsp extends Module {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kuro-itemesp.txt");
    private static final String[] DEFAULTS = {"diamond", "netherite", "ancient_debris", "totem", "elytra", "enchanted_golden_apple",
            "shulker_box", "nether_star", "beacon", "trident", "mace", "dragon_egg"};

    private final Setting.Hue color = add(new Setting.Hue("Color", 0.55f).wheel());
    private final Setting.Hue goodColor = add(new Setting.Hue("Highlight color", 0.12f).wheel());
    private final Setting.Num range = add(new Setting.Num("Range", 64, 8, 256, 8));
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 1, 1, 3, 1));
    private final Setting.Bool boxes = add(new Setting.Bool("Boxes", true));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", true));
    private final Setting.Bool distance = add(new Setting.Bool("Show distance", true));
    private final Setting.Bool tracers = add(new Setting.Bool("Tracers", false));
    private final Setting.Bool onlyList = add(new Setting.Bool("Only highlighted items", false));
    private final Setting.Num maxLabels = add(new Setting.Num("Max labels", 30, 5, 80, 5));

    private final List<String> words = new ArrayList<>();

    private record Drop(Item item, Vec3 pos, boolean hl) {}

    private static final class Cluster {
        final Item item; final String name; final boolean hl; final Vec3 pos; final double dist;
        int count;
        Cluster(Item item, String name, int count, boolean hl, Vec3 pos, double dist) {
            this.item = item; this.name = name; this.count = count; this.hl = hl; this.pos = pos; this.dist = dist;
        }
    }

    private final List<Drop> drops = new ArrayList<>();
    private final List<Cluster> clusters = new ArrayList<>();
    private final double[] pt = new double[2];

    public ItemEsp() {
        super("Item ESP", Category.RENDER);
        add(new Setting.Text("Highlight word (Enter adds)", this::addWord));
        add(new Setting.Button("Show highlight list", () -> say("Highlighted: " + (words.isEmpty() ? "(none)" : String.join(", ", words)))));
        add(new Setting.Button("Reset highlight list", () -> { words.clear(); for (String w : DEFAULTS) words.add(w); save(); say("Highlight list reset."); }));
        add(new Setting.Button("Clear highlight list", () -> { words.clear(); save(); say("Highlight list cleared."); }));
        load();
    }

    // ------------------------------------------------------------------ highlight list

    private void addWord(String text) {
        String w = text.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (w.isEmpty()) return;
        if (words.remove(w)) { save(); say("Removed \"" + w + "\" from the highlight list."); return; }   // typing a word again removes it
        words.add(w);
        save();
        say("Highlighting items with \"" + w + "\" in their id. (Type it again to remove it.)");
    }

    private boolean matches(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        for (String w : words) if (path.contains(w)) return true;
        return false;
    }

    private void load() {
        try {
            if (Files.exists(FILE)) {
                for (String line : Files.readAllLines(FILE)) { String w = line.trim(); if (!w.isEmpty()) words.add(w); }
                return;
            }
        } catch (IOException ignored) {}
        for (String w : DEFAULTS) words.add(w);
    }

    private void save() {
        try { Files.write(FILE, words); } catch (IOException ignored) {}
    }

    private static void say(String s) { CommandManager.say(s); }

    // ------------------------------------------------------------------ per frame

    private void gather() {
        drops.clear();
        clusters.clear();
        if (mc.level == null || mc.player == null) return;
        Vec3 cam = Projector.camPos;
        double r2 = range.value * range.value;
        float tick = Projector.tickProgress;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ItemEntity ie)) continue;
            ItemStack st = ie.getItem();
            if (st.isEmpty()) continue;
            Vec3 p = ie.getPosition(tick);
            double dx = p.x - cam.x, dy = p.y - cam.y, dz = p.z - cam.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) continue;
            boolean hl = matches(st.getItem());
            if (onlyList.value && !hl) continue;
            drops.add(new Drop(st.getItem(), p, hl));

            Cluster c = null;
            for (Cluster k : clusters) {
                if (k.item == st.getItem() && k.pos.distanceToSqr(p) < 4.0) { c = k; break; }
            }
            if (c == null) clusters.add(new Cluster(st.getItem(), st.getHoverName().getString(), st.getCount(), hl, p, Math.sqrt(d2)));
            else c.count += st.getCount();
        }
        clusters.sort((a, b) -> Double.compare(a.dist, b.dist));
    }

    @Override
    public void onWorld() {
        gather();
        if (!boxes.value) return;
        int t = thickness.intValue(), n = 0;
        for (Drop d : drops) {
            if (n++ >= 400) break;
            int c = (d.hl ? goodColor : color).rgb();
            Vec3 p = d.pos;
            WorldGfx.box(p.x - 0.22, p.y, p.z - 0.22, p.x + 0.22, p.y + 0.45, p.z + 0.22,
                    0xFF000000 | c, 0x30000000 | c, t, d.hl);
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        if (!labels.value && !tracers.value) return;
        double cx = Projector.width() / 2.0, cy = Projector.height() / 2.0;
        int shown = 0;
        for (Cluster c : clusters) {
            if (shown >= maxLabels.intValue()) break;
            int rgb = (c.hl ? goodColor : color).rgb();
            if (tracers.value) {
                Projector.projectOrPoint(c.pos.x, c.pos.y + 0.2, c.pos.z, pt);
                Gfx.line(g, cx, cy, pt[0], pt[1], thickness.intValue(), Look.withAlpha(rgb, 0xC8));
            }
            if (!labels.value || !Projector.project(c.pos.x, c.pos.y + 0.75, c.pos.z, pt)) continue;
            String text = c.name + (c.count > 1 ? " x" + c.count : "") + (distance.value ? "  " + (int) Math.round(c.dist) + "m" : "");
            Look.tagClamped(g, mc.font, text, (int) pt[0], (int) pt[1] - 14, 0xFF000000 | rgb);
            shown++;
        }
    }
}
