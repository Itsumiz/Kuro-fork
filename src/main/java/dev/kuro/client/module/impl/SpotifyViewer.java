package dev.kuro.client.module.impl;

import dev.kuro.client.util.SdfText;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shows what Spotify is playing in a small HUD plate. No login or API key: it asks the operating system.
 *  - Windows: reads the title of the Spotify desktop window ("Artist - Song" while playing). No progress bar.
 *  - Linux:   `playerctl` (MPRIS), gives artist, title, play state and position.
 *  - macOS:   AppleScript, gives artist, title, play state and position.
 * Works with the Spotify desktop app only (not the web player). Polling runs on its own thread, never the game thread.
 */
public class SpotifyViewer extends Module implements HudElement {
    private final Setting.Num x = add(new Setting.Num("X", 6, 0, 4000, 1));
    private final Setting.Num y = add(new Setting.Num("Y", 40, 0, 4000, 1));
    private final Setting.Num refresh = add(new Setting.Num("Refresh (s)", 2, 1, 10, 1));
    private final Setting.Bool progress = add(new Setting.Bool("Progress bar", true));   // Linux / macOS only
    private final Setting.Bool hidePaused = add(new Setting.Bool("Hide when paused", false));

    private volatile String artist = "", title = "";
    private volatile boolean playing, running;
    private volatile double pos = -1, len = -1;          // seconds; -1 = unknown
    private volatile long stamp;                          // when pos was read, to keep the bar moving between polls
    private volatile String status = "Starting...";
    private Thread worker;
    private volatile boolean alive;

    private int lastW = 110, lastH = 24;

    public SpotifyViewer() { super("Spotify", Category.RENDER); }

    @Override public Setting.Num hudX() { return x; }
    @Override public Setting.Num hudY() { return y; }
    @Override public int hudW() { return lastW; }
    @Override public int hudH() { return lastH; }
    @Override public String hudName() { return name; }

    @Override
    protected void onEnable() {
        alive = true;
        worker = new Thread(this::loop, "kuro-spotify");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    protected void onDisable() {
        alive = false;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private void loop() {
        while (alive) {
            try { poll(); } catch (InterruptedException e) { return; } catch (Throwable t) { status = "Spotify not reachable"; running = false; }
            try { Thread.sleep((long) (refresh.value * 1000)); } catch (InterruptedException e) { return; }
        }
    }

    // ---------------- polling ----------------

    private void poll() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) pollWindows();
        else if (os.contains("mac")) pollMac();
        else pollLinux();
    }

    private void pollWindows() throws Exception {
        String out = run("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
                        + "(Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle } | Select-Object -First 1).MainWindowTitle");
        out = out == null ? "" : out.trim();
        if (out.isEmpty()) { running = false; status = "Spotify is not running"; return; }
        running = true;
        pos = len = -1;
        // idle / paused windows are titled "Spotify", "Spotify Free" or "Spotify Premium"
        if (out.equals("Spotify") || out.startsWith("Spotify Free") || out.startsWith("Spotify Premium")) {
            playing = false; status = "Paused";
            return;
        }
        int i = out.indexOf(" - ");
        if (i > 0) { artist = out.substring(0, i); title = out.substring(i + 3); }
        else { artist = ""; title = out; }
        playing = true;
        status = "";
    }

    private void pollLinux() throws Exception {
        String out = run("playerctl", "-p", "spotify", "metadata", "--format",
                "{{status}}\t{{artist}}\t{{title}}\t{{position}}\t{{mpris:length}}");
        if (out == null || out.isBlank()) { running = false; status = "Spotify is not running (needs playerctl)"; return; }
        parse(out.trim().split("\t", -1), 1_000_000.0);
    }

    private void pollMac() throws Exception {
        String script = "if application \"Spotify\" is running then\n"
                + "tell application \"Spotify\"\n"
                + "return (player state as string) & \"\\t\" & (artist of current track) & \"\\t\" & (name of current track) & \"\\t\" & (player position as string) & \"\\t\" & ((duration of current track) as string)\n"
                + "end tell\nend if";
        String out = run("osascript", "-e", script);
        if (out == null || out.isBlank()) { running = false; status = "Spotify is not running"; return; }
        String[] f = out.trim().split("\t", -1);
        // Spotify reports the position in seconds and the duration in milliseconds
        if (f.length >= 5) {
            try { f[3] = String.valueOf(Double.parseDouble(f[3].replace(',', '.')) * 1_000_000.0); } catch (Exception ignored) {}
            try { f[4] = String.valueOf(Double.parseDouble(f[4].replace(',', '.')) * 1000.0); } catch (Exception ignored) {}
        }
        parse(f, 1_000_000.0);
    }

    /** f = state, artist, title, position, length (position / length in microseconds). */
    private void parse(String[] f, double perSecond) {
        if (f.length < 3) { running = false; return; }
        running = true;
        playing = f[0].equalsIgnoreCase("playing");
        artist = f[1];
        title = f[2];
        pos = len = -1;
        try { if (f.length >= 5) { pos = Double.parseDouble(f[3]) / perSecond; len = Double.parseDouble(f[4]) / perSecond; } }
        catch (Exception ignored) {}
        stamp = System.currentTimeMillis();
        status = playing ? "" : "Paused";
    }

    private static String run(String... cmd) throws Exception {
        Process p;
        try { p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); }
        catch (java.io.IOException e) { return null; }                        // command not installed
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) return null;
            return p.exitValue() == 0 ? sb.toString() : null;
        } finally {
            p.destroyForcibly();
        }
    }

    // ---------------- drawing ----------------

    @Override
    public void onHud(GuiGraphics g) {
        if (mc.player == null) return;
        boolean hasTrack = running && !(title.isEmpty() && artist.isEmpty());
        if (hidePaused.value && (!hasTrack || !playing)) return;

        Font tr = mc.font;
        String line1 = hasTrack ? title : status;
        String line2 = hasTrack ? (artist.isEmpty() ? (playing ? "" : "Paused") : artist + (playing ? "" : "  (paused)")) : "";
        boolean bar = progress.value && hasTrack && len > 0;

        int w = Math.max(110, Math.min(220, Math.max(SdfText.w(line1), SdfText.w(line2)) + 22));
        int h = 14 + (line2.isEmpty() ? 0 : 11) + (bar ? 6 : 0) + 4;
        lastW = w; lastH = h;
        int x1 = HudElement.clampX(x, w), y1 = HudElement.clampY(y, h);

        Look.panel(g, x1, y1, x1 + w, y1 + h, playing ? Look.GOOD : Look.MUTED);
        int tx = x1 + 9, ty = y1 + 5;
        SdfText.ui(g, trim(tr, line1, w - 16), tx, ty, Look.TEXT, false);
        ty += 11;
        if (!line2.isEmpty()) { SdfText.ui(g, trim(tr, line2, w - 16), tx, ty, Look.MUTED, false); ty += 11; }
        if (bar) {
            double now = pos + (playing ? (System.currentTimeMillis() - stamp) / 1000.0 : 0);   // keep it moving between polls
            Look.bar(g, tx, ty, w - 18, 2, now / len, Look.GOOD);
        }
    }

    private static String trim(Font tr, String s, int max) {
        if (SdfText.w(s) <= max) return s;
        while (s.length() > 1 && SdfText.w(s + "...") > max) s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
