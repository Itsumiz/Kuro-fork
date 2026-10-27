package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.HudPanel;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen toasts: "Module - Enabled / Disabled" whenever a module is switched on or off (menu, keybind, command, or
 * the module turning itself off). Module.setEnabled calls toggled(); the toasts are drawn by this module's onHud.
 * Config loads and bulk switches (Toggle All, panic) are silent and show one summary toast instead.
 */
public class Notifications extends Module {
    private static final int MAX = 5;
    private static final long FADE_MS = 260;

    private static Notifications instance;
    private static int quiet;
    private static final List<Toast> toasts = new ArrayList<>();

    private final Setting.Num duration = add(new Setting.Num("Duration (s)", 2.5, 1, 8, 0.5));
    private final Setting.Num position = add(new Setting.Num("Position", 0, 0, 2, 1).labelled("Bottom right", "Bottom left", "Top centre"));
    private final Setting.Num sound = add(new Setting.Num("Sound", 1, 0, 3, 1).labelled("Off", "Tone", "Notify", "Notify 6"));
    private static final String[][] SOUNDS = {{"tone_enable", "tone_disable"}, {"notify_enable", "notify_disable"}, {"notify_type6_enabled", "notify_type6_disabled"}};
    private final Setting.Bool showOn = add(new Setting.Bool("Show enabled", true));
    private final Setting.Bool showOff = add(new Setting.Bool("Show disabled", true));

    private static final class Toast {
        final String key, title, sub; final int accent; final long start; long life;
        Toast(String key, String title, String sub, int accent, long life) {
            this.key = key; this.title = title; this.sub = sub; this.accent = accent; this.life = life; this.start = System.currentTimeMillis();
        }
    }

    public Notifications() { super("Notifications", Category.RENDER); instance = this; }

    /** Silence per-module toasts while true (config load, bulk switches). Calls nest. */
    public static void quiet(boolean on) { quiet = Math.max(0, quiet + (on ? 1 : -1)); }

    /** Called by Module.setEnabled after the state changed. */
    public static void toggled(Module m, boolean on) {
        if (instance == null || !instance.isEnabled() || quiet > 0) return;
        if (m.holdToUse()) return;                      // Zoom / Freelook flip on and off with the key: no toast every time
        if (m instanceof GlassUi || m instanceof SidebarUi || m instanceof BlobUi || m instanceof RadialMenu || m instanceof MenuModule || m instanceof FoundList) return;
        if (on ? !instance.showOn.value : !instance.showOff.value) return;
        int s = instance.sound.intValue();
        if (s > 0 && net.minecraft.client.Minecraft.getInstance().player != null) dev.kuro.client.util.KuroSounds.play(SOUNDS[s - 1][on ? 0 : 1]);
        post(m.name, m.name, on ? "Enabled" : "Disabled", on ? Look.GOOD : Look.BAD);
    }

    public static boolean active() { return instance != null && instance.isEnabled(); }

    /** A free-form toast (used for bulk actions). */
    public static void info(String title, String sub, boolean good) {
        if (instance == null || !instance.isEnabled()) return;
        post("info:" + title, title, sub, good ? Look.GOOD : Look.WARN);
    }

    private static void post(String key, String title, String sub, int accent) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;     // nothing to show while loading / on the title screen
        synchronized (toasts) {
            toasts.removeIf(t -> t.key.equals(key));            // flipping the same module again replaces its toast
            toasts.add(new Toast(key, title, sub, accent, (long) (instance.duration.value * 1000)));
            while (toasts.size() > MAX) toasts.remove(0);
        }
    }

    @Override
    protected void onDisable() { synchronized (toasts) { toasts.clear(); } }

    /** The spot the newest toast takes. Drag it in the HUD layout editor: toasts then stack away from the nearest screen edge. */
    private final HudPanel anchor = new HudPanel(this, "Notifications");
    private static final int TOAST_W = 130, TOAST_H = 26;

    @Override
    public List<HudElement> hudElements() { return List.of(anchor); }

    @Override
    public void onHud(GuiGraphics g) {
        long now = System.currentTimeMillis();
        int pos = position.intValue();
        int sw = Projector.width(), sh = Projector.height();
        boolean editing = dev.kuro.client.gui.HudEditScreen.editing();

        // where the newest toast goes when nobody has moved the panel (the "Position" setting)
        int autoX = pos == 0 ? sw - TOAST_W - 6 : pos == 1 ? 6 : (sw - TOAST_W) / 2;
        int autoY = pos == 2 ? 4 : sh - 52 - TOAST_H;
        anchor.place(autoX, autoY, TOAST_W, TOAST_H);
        boolean free = anchor.moved();
        boolean growUp = free ? anchor.y() + TOAST_H / 2 > sh / 2 : pos != 2;
        boolean alignRight = free ? anchor.x() + TOAST_W / 2 > sw / 2 : pos == 0;

        int row = 0;
        synchronized (toasts) {
            toasts.removeIf(t -> now - t.start > t.life + FADE_MS);
            if (toasts.isEmpty() && editing) {                   // layout editor: a stand-in toast so the panel can be found and moved
                Look.panel(g, anchor.x(), anchor.y(), anchor.x() + TOAST_W, anchor.y() + TOAST_H, Look.GOOD);
                SdfText.ui(g, "Notifications", anchor.x() + 9, anchor.y() + 3, Look.TEXT, false);
                SdfText.ui(g, "Enabled", anchor.x() + 9, anchor.y() + 14, Look.GOOD, false);
                return;
            }
            for (int i = toasts.size() - 1; i >= 0; i--) {      // newest nearest the corner
                Toast t = toasts.get(i);
                long age = now - t.start;
                float in = Math.min(1f, age / (float) FADE_MS);
                float out = age > t.life ? Math.min(1f, (age - t.life) / (float) FADE_MS) : 0f;
                float slide = (1f - (float) Math.pow(1f - in, 3)) * (1f - out * out);   // 0 = off screen, 1 = in place

                int w = Math.max(96, Math.max(SdfText.w(t.title), SdfText.w(t.sub)) + 22), h = TOAST_H;
                int x1, y1;
                if (!free && pos == 2) {                         // top centre: slides down, stacks downward
                    x1 = (sw - w) / 2;
                    y1 = Math.round(4 + row * (h + 4) - (1f - slide) * (h + 8));
                } else {
                    int off = Math.round((1f - slide) * (w + 8));
                    int baseX = alignRight ? anchor.x() + TOAST_W - w : anchor.x();
                    x1 = alignRight ? baseX + off : baseX - off;
                    y1 = growUp ? anchor.y() - row * (h + 4) : anchor.y() + row * (h + 4);
                }
                Look.panel(g, x1, y1, x1 + w, y1 + h, t.accent);
                SdfText.ui(g, t.title, x1 + 9, y1 + 3, Look.TEXT, false);
                SdfText.ui(g, t.sub, x1 + 9, y1 + 14, t.accent, false);
                row++;
            }
        }
    }
}
