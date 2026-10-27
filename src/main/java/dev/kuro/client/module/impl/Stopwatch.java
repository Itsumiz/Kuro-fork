package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

import java.util.Locale;

/**
 * A stopwatch or countdown on the HUD. Start / pause / reset from the menu or with the .timer command:
 *   .timer start | pause | reset | set <1h30m | 5m | 90s | 2:30>   (set = countdown length, switches to countdown mode)
 * The countdown plays a chime and shows a toast when it reaches zero. Keeps counting in real time while paused menus are open.
 */
public class Stopwatch extends Module implements HudElement {
    private static Stopwatch instance;
    public static Stopwatch get() { return instance; }

    private final Setting.Num x = add(new Setting.Num("X", 6, 0, 4000, 1));
    private final Setting.Num y = add(new Setting.Num("Y", 100, 0, 4000, 1));
    private final Setting.Num mode = add(new Setting.Num("Mode", 1, 1, 2, 1).labelled("Stopwatch", "Countdown"));
    private final Setting.Num countdown = add(new Setting.Num("Countdown (seconds)", 300, 5, 7200, 5));
    private final Setting.Bool tenths = add(new Setting.Bool("Tenths of a second", false));
    private final Setting.Bool chime = add(new Setting.Bool("Chime at zero", true));

    private long accumMs;             // time counted before the current run
    private long startedMs = -1;      // wall-clock start of the current run, -1 = not running
    private boolean fired;            // the countdown already rang
    private int lastW = 60, lastH = 15;

    public Stopwatch() {
        super("Stopwatch", Category.RENDER);
        instance = this;
        add(new Setting.Button("Start / pause", this::startPause));
        add(new Setting.Button("Reset", this::reset));
    }

    @Override public Setting.Num hudX() { return x; }
    @Override public Setting.Num hudY() { return y; }
    @Override public int hudW() { return lastW; }
    @Override public int hudH() { return lastH; }
    @Override public String hudName() { return name; }

    private boolean running() { return startedMs >= 0; }
    private long elapsed() { return accumMs + (running() ? System.currentTimeMillis() - startedMs : 0L); }
    private boolean countingDown() { return mode.intValue() == 2; }

    public void startPause() {
        if (running()) { accumMs = elapsed(); startedMs = -1; }
        else {
            if (countingDown() && elapsed() >= (long) (countdown.value * 1000)) { accumMs = 0; fired = false; }
            startedMs = System.currentTimeMillis();
        }
    }

    public void reset() { accumMs = 0; startedMs = -1; fired = false; }

    // ------------------------------------------------------------------ the countdown ringing

    @Override
    public void onTick() {
        if (!countingDown() || !running() || fired) return;
        if (elapsed() < (long) (countdown.value * 1000)) return;
        fired = true;
        accumMs = (long) (countdown.value * 1000);
        startedMs = -1;
        Notifications.info("Timer", "Time is up", true);
        CommandManager.say("Timer finished.");
        if (chime.value && mc.player != null) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
    }

    // ------------------------------------------------------------------ HUD

    @Override
    public void onHud(GuiGraphics g) {
        long ms = countingDown() ? Math.max(0L, (long) (countdown.value * 1000) - elapsed()) : elapsed();
        String time = format(ms);
        String tag = countingDown() ? (fired ? "Time is up" : running() ? "Countdown" : "Countdown (paused)")
                                    : (running() ? "Stopwatch" : "Stopwatch (paused)");
        int tw = Math.max(SdfText.w(time), SdfText.w(time.replaceAll("[0-9]", "8")));
        int w = Math.max(tw, SdfText.w(tag)) + 18, h = 26;
        lastW = w; lastH = h;
        int x1 = HudElement.clampX(x, w), y1 = HudElement.clampY(y, h);
        boolean flash = fired && (System.currentTimeMillis() / 500) % 2 == 0;
        Look.panel(g, x1, y1, x1 + w, y1 + h, flash ? Look.BAD : running() ? Look.GOOD : Look.ACCENT);
        SdfText.ui(g, time, x1 + 9, y1 + 4, Look.TEXT, false);
        SdfText.ui(g, tag, x1 + 9, y1 + 15, Look.MUTED, false);
    }

    private String format(long ms) {
        long totalSec = ms / 1000, h = totalSec / 3600, m = (totalSec / 60) % 60, s = totalSec % 60;
        String base = h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%02d:%02d", m, s);
        return tenths.value ? base + "." + (ms / 100) % 10 : base;
    }

    // ------------------------------------------------------------------ .timer command

    public void command(String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "resume" -> { show(); if (!running()) startPause(); }
            case "pause", "stop" -> { if (running()) startPause(); }
            case "reset", "clear" -> reset();
            case "toggle" -> { show(); startPause(); }
            case "stopwatch" -> { mode.value = 1; reset(); show(); }
            case "set", "countdown" -> {
                long sec = a.length >= 2 ? parseSeconds(a[1]) : -1;
                if (sec < 5 || sec > 7200) { CommandManager.say("Usage: .timer set <1h30m | 5m | 90s | 2:30>   (5 seconds to 2 hours)"); return; }
                countdown.value = sec;
                mode.value = 2;
                reset();
                show();
                CommandManager.say("Countdown set to " + format(sec * 1000) + ". Start it with .timer start");
            }
            default -> CommandManager.say(".timer start | pause | reset | stopwatch | set <1h30m | 5m | 90s | 2:30>");
        }
    }

    private void show() { if (!isEnabled()) setEnabled(true); }

    /** "1h30m", "5m", "90s", "90", "2:30" (m:ss) or "1:02:03" -> seconds, or -1. */
    static long parseSeconds(String t) {
        t = t.toLowerCase(Locale.ROOT).trim();
        try {
            if (t.contains(":")) {
                String[] p = t.split(":");
                long total = 0;
                for (String part : p) total = total * 60 + Long.parseLong(part);
                return p.length <= 3 ? total : -1;
            }
            long total = 0, cur = 0;
            boolean any = false;
            for (char c : t.toCharArray()) {
                if (Character.isDigit(c)) { cur = cur * 10 + (c - '0'); any = true; }
                else if (c == 'h') { total += cur * 3600; cur = 0; }
                else if (c == 'm') { total += cur * 60; cur = 0; }
                else if (c == 's') { total += cur; cur = 0; }
                else return -1;
            }
            return any ? total + cur : -1;                 // a bare number = seconds
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
