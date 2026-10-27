package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Small HUD plate: FPS, 1% low, frame time, ping, server TPS, memory, entities. Drag it with .hudedit.
 * Also (optional) drops the frame rate while the game window is not in focus, and puts your own limit back afterwards.
 *
 * TPS is worked out from how often the server sends the time (every 20 game ticks): 20 / seconds between two packets.
 * It is an estimate, like on other clients; a lag spike on your own connection can nudge it for a moment.
 */
public class Performance extends Module implements HudElement {
    private final Setting.Num x = add(new Setting.Num("X", 6, 0, 4000, 1));
    private final Setting.Num y = add(new Setting.Num("Y", 38, 0, 4000, 1));
    private final Setting.Bool fps = add(new Setting.Bool("FPS", true));
    private final Setting.Bool low = add(new Setting.Bool("1% low", true));
    private final Setting.Bool frameTime = add(new Setting.Bool("Frame time", false));
    private final Setting.Bool ping = add(new Setting.Bool("Ping", true));
    private final Setting.Bool tps = add(new Setting.Bool("TPS", true));
    private final Setting.Bool memory = add(new Setting.Bool("Memory", false));
    private final Setting.Bool entities = add(new Setting.Bool("Entities", false));
    private final Setting.Bool limit = add(new Setting.Bool("Limit FPS when unfocused", false));
    private final Setting.Num limitFps = add(new Setting.Num("Unfocused FPS", 20, 10, 60, 10));

    private int lastW = 80, lastH = 30;

    // frame times for the 1% low
    private final long[] frames = new long[300];
    private int frameCount, frameHead;
    private long lastFrameNs, lastCalcNs;
    private double lowFps = -1, avgMs = 0;

    // TPS from the time packets
    private static final double[] gaps = new double[6];
    private static int gapCount, gapHead;
    private static long lastTimeNs;

    private int savedLimit = -1;

    public Performance() { super("Performance", Category.RENDER); }

    @Override public Setting.Num hudX() { return x; }
    @Override public Setting.Num hudY() { return y; }
    @Override public int hudW() { return lastW; }
    @Override public int hudH() { return lastH; }
    @Override public String hudName() { return name; }

    // ------------------------------------------------------------------ TPS (called from ClientPlayNetworkHandlerMixin)

    public static void onTimePacket() {
        long now = System.nanoTime();
        if (lastTimeNs != 0L) {
            double sec = (now - lastTimeNs) / 1.0E9;
            if (sec >= 0.2 && sec <= 6.0) {
                gaps[gapHead] = sec;
                gapHead = (gapHead + 1) % gaps.length;
                gapCount = Math.min(gapCount + 1, gaps.length);
            } else if (sec > 6.0) {                    // a long silence: joined a world / was frozen. Start over.
                gapCount = 0; gapHead = 0;
            }
        }
        lastTimeNs = now;
    }

    /** -1 = no estimate yet. */
    private static double tps() {
        if (gapCount < 2) return -1;
        double sum = 0;
        for (int i = 0; i < gapCount; i++) sum += gaps[i];
        return Math.min(20.0, 20.0 / (sum / gapCount));
    }

    // ------------------------------------------------------------------ unfocused limiter

    @Override
    public void onTick() {
        if (mc.options == null) return;
        if (limit.value && mc.player != null && !mc.isWindowActive()) {
            if (savedLimit < 0) {
                savedLimit = mc.options.framerateLimit().get();
                mc.options.framerateLimit().set(Math.min(savedLimit, limitFps.intValue()));
            }
        } else {
            restoreLimit();
        }
    }

    private void restoreLimit() {
        if (savedLimit >= 0 && mc.options != null) mc.options.framerateLimit().set(savedLimit);
        savedLimit = -1;
    }

    @Override protected void onDisable() { restoreLimit(); }

    // ------------------------------------------------------------------ HUD

    private void sampleFrame() {
        long now = System.nanoTime();
        if (lastFrameNs != 0L) {
            long d = now - lastFrameNs;
            if (d < 2_000_000_000L) {
                frames[frameHead] = d;
                frameHead = (frameHead + 1) % frames.length;
                frameCount = Math.min(frameCount + 1, frames.length);
            }
        }
        lastFrameNs = now;
        if (now - lastCalcNs > 500_000_000L && frameCount >= 30) {          // twice a second is plenty
            lastCalcNs = now;
            long[] copy = Arrays.copyOf(frames, frameCount);
            Arrays.sort(copy);
            int worst = Math.max(3, frameCount / 100);
            double sumWorst = 0, sumAll = 0;
            for (int i = 0; i < worst; i++) sumWorst += copy[frameCount - 1 - i];
            for (int i = 0; i < frameCount; i++) sumAll += copy[i];
            lowFps = 1.0E9 / (sumWorst / worst);
            avgMs = sumAll / frameCount / 1.0E6;
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        sampleFrame();
        List<String[]> rows = new ArrayList<>();           // {label, value, colour as hex}
        if (fps.value) {
            int f = mc.getFps();
            rows.add(row("FPS", String.valueOf(f), f >= 60 ? Look.GOOD : f >= 30 ? Look.WARN : Look.BAD));
        }
        if (low.value && lowFps > 0) {
            rows.add(row("1% low", String.valueOf((int) Math.round(lowFps)), lowFps >= 45 ? Look.GOOD : lowFps >= 25 ? Look.WARN : Look.BAD));
        }
        if (frameTime.value && avgMs > 0) {
            rows.add(row("Frame", String.format(Locale.ROOT, "%.1f ms", avgMs), avgMs <= 16.7 ? Look.GOOD : avgMs <= 33.4 ? Look.WARN : Look.BAD));
        }
        if (ping.value && mc.player != null && mc.getConnection() != null) {
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null && info.getLatency() > 0) {
                int p = info.getLatency();
                rows.add(row("Ping", p + " ms", p < 80 ? Look.GOOD : p < 150 ? Look.WARN : Look.BAD));
            }
        }
        if (tps.value) {
            double t = tps();
            if (t >= 0) rows.add(row("TPS", String.format(Locale.ROOT, "%.1f", t), t >= 19 ? Look.GOOD : t >= 15 ? Look.WARN : Look.BAD));
        }
        if (memory.value) {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
            double frac = max == 0 ? 0 : (double) used / max;
            rows.add(row("Memory", used + " / " + max + " MB", frac < 0.7 ? Look.GOOD : frac < 0.9 ? Look.WARN : Look.BAD));
        }
        if (entities.value && mc.level != null) {
            int n = 0;
            for (Entity ignored : mc.level.entitiesForRendering()) n++;
            rows.add(row("Entities", String.valueOf(n), Look.TEXT));
        }
        if (rows.isEmpty()) return;

        int labelW = 0, valueW = 0;
        for (String[] r : rows) {
            labelW = Math.max(labelW, SdfText.w(r[0]));
            valueW = Math.max(valueW, SdfText.w(r[1].replaceAll("[0-9]", "8")));   // widest digit: no jitter while numbers change
        }
        int w = labelW + valueW + 30, h = 8 + rows.size() * 11;
        lastW = w; lastH = h;
        int x1 = HudElement.clampX(x, w), y1 = HudElement.clampY(y, h);
        Look.panel(g, x1, y1, x1 + w, y1 + h, Look.ACCENT);
        int ty = y1 + 5;
        for (String[] r : rows) {
            SdfText.ui(g, r[0], x1 + 10, ty, Look.MUTED, false);
            SdfText.ui(g, r[1], x1 + w - 9 - SdfText.w(r[1]), ty, (int) Long.parseLong(r[2], 16), false);
            ty += 11;
        }
    }

    private static String[] row(String label, String value, int argb) {
        return new String[]{label, value, Long.toHexString(argb & 0xFFFFFFFFL)};
    }
}
