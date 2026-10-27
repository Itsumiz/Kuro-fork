package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays back the macro recorded by "Macro Record". Bind a key to this module: pressing it plays the macro,
 * pressing it again stops it. The module is "on" only while the macro is playing.
 *
 * A macro is one frame per game tick: which game keys/mouse buttons were held (movement, jump, sneak,
 * sprint, attack, use, drop, swap hands, hotbar 1-9) and how far the camera turned that tick.
 * The Speed slider plays it back faster or slower (frames are skipped or held, nothing is lost: a quick click
 * in a skipped frame still registers). The macro is saved to config/kuro-macro.txt.
 */
public class Macro extends Module {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kuro-macro.txt");
    private static Macro instance;

    public record Frame(int mask, float dyaw, float dpitch) {}

    /** The recorded macro, shared with MacroRecord. */
    public static final List<Frame> frames = new ArrayList<>();
    /** True while MacroRecord is recording. */
    public static boolean recording;

    private static KeyMapping[] keyList;

    private final Setting.Num speed = add(new Setting.Num("Speed", 1.0, 0.25, 4.0, 0.25));
    private final Setting.Bool loop = add(new Setting.Bool("Loop", false));
    private final Setting.Bool lookToo = add(new Setting.Bool("Replay camera turns", true));

    private int idx, prevMask;
    private double acc;

    public Macro() {
        super("Macro", Category.PLAYER);
        instance = this;
        add(new Setting.Button("Clear macro", () -> { frames.clear(); save(); say("Macro cleared."); }));
        add(new Setting.Button("Show info", () -> say(frames.isEmpty() ? "No macro recorded."
                : frames.size() + " ticks (" + String.format("%.1f", frames.size() / 20.0) + "s at normal speed)")));
        load();
    }

    public static Macro get() { return instance; }

    /** The keys a macro records and replays, in bit order. */
    public static KeyMapping[] keys() {
        if (keyList == null) {
            Options o = mc.options;
            List<KeyMapping> l = new ArrayList<>(List.of(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump,
                    o.keyShift, o.keySprint, o.keyAttack, o.keyUse, o.keyDrop, o.keySwapOffhand));
            for (KeyMapping k : o.keyHotbarSlots) l.add(k);
            keyList = l.toArray(new KeyMapping[0]);
        }
        return keyList;
    }

    static void say(String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Macro] " + s), false);
    }

    @Override
    protected void onEnable() {
        if (mc.player == null) { setEnabled(false); return; }
        if (recording) { say("Stop recording first."); setEnabled(false); return; }
        if (frames.isEmpty()) { say("Nothing recorded yet. Use the Macro Record module first."); setEnabled(false); return; }
        idx = 0; acc = 1.0; prevMask = 0;       // acc = 1 so the first frame plays on the very first tick
    }

    @Override protected void onDisable() { releaseAll(); }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) { setEnabled(false); return; }
        if (mc.screen != null) { releaseAll(); return; }       // paused while a menu is open

        acc += speed.value;
        int steps = (int) acc;
        acc -= steps;
        if (steps == 0) return;                                        // slow motion: hold the current frame

        int mask = prevMask, pressedEdges = 0;
        float dyaw = 0, dpitch = 0;
        for (int n = 0; n < steps; n++) {
            if (idx >= frames.size()) {
                if (loop.value) idx = 0;
                else { apply(mask, pressedEdges, dyaw, dpitch); setEnabled(false); return; }
            }
            Frame f = frames.get(idx++);
            pressedEdges |= f.mask() & ~mask;                          // a press inside a skipped frame still counts
            mask = f.mask();
            dyaw += f.dyaw();
            dpitch += f.dpitch();
        }
        apply(mask, pressedEdges, dyaw, dpitch);
    }

    private void apply(int mask, int edges, float dyaw, float dpitch) {
        KeyMapping[] keys = keys();
        for (int i = 0; i < keys.length; i++) {
            boolean down = (mask & (1 << i)) != 0;
            keys[i].setDown(down);
            if ((edges & (1 << i)) != 0) KeyMapping.click(KeyBindingHelper.getBoundKeyOf(keys[i]));
        }
        prevMask = mask;
        if (lookToo.value && (dyaw != 0 || dpitch != 0)) {
            mc.player.setYRot(mc.player.getYRot() + dyaw);
            mc.player.setXRot(Mth.clamp(mc.player.getXRot() + dpitch, -90f, 90f));
        }
    }

    private void releaseAll() {
        for (KeyMapping k : keys()) k.setDown(false);
        KeyMapping.setAll();                              // re-sync with the keys that are physically held
        prevMask = 0;
    }

    // ---- saving: one line per tick: mask <space> dyaw <space> dpitch ----
    public static void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (Frame f : frames) lines.add(f.mask() + " " + f.dyaw() + " " + f.dpitch());
            Files.createDirectories(FILE.getParent());
            Files.write(FILE, lines);
        } catch (IOException ignored) {}
    }

    private static void load() {
        try {
            if (!Files.exists(FILE)) return;
            for (String line : Files.readAllLines(FILE)) {
                String[] p = line.trim().split(" ");
                if (p.length < 3) continue;
                try { frames.add(new Frame(Integer.parseInt(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]))); }
                catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
    }
}
