package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Real-world (IRL) time, drawn as a small HUD plate. Position, 12/24h, seconds and date are all settings. */
public class Clock extends Module implements HudElement {
    private static final DateTimeFormatter T24 = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter T24S = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter T12 = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter T12S = DateTimeFormatter.ofPattern("h:mm:ss a");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE d MMM");

    private final Setting.Num x = add(new Setting.Num("X", 6, 0, 4000, 1));
    private final Setting.Num y = add(new Setting.Num("Y", 20, 0, 4000, 1));
    private final Setting.Bool clock24 = add(new Setting.Bool("24h Clock", true));
    private final Setting.Bool seconds = add(new Setting.Bool("Seconds", true));
    private final Setting.Bool date = add(new Setting.Bool("Date", false));

    private int lastW = 60, lastH = 15;

    public Clock() { super("Clock", Category.RENDER); }

    @Override public Setting.Num hudX() { return x; }
    @Override public Setting.Num hudY() { return y; }
    @Override public int hudW() { return lastW; }
    @Override public int hudH() { return lastH; }
    @Override public String hudName() { return name; }

    @Override
    public void onHud(GuiGraphics g) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter f = clock24.value ? (seconds.value ? T24S : T24) : (seconds.value ? T12S : T12);
        String time = now.format(f);
        String day = date.value ? now.format(DATE) : "";

        // the plate is sized from a string of 8s (the widest digit) so it does not jitter as the digits change
        int tw = Math.max(SdfText.w(time), SdfText.w(time.replaceAll("[0-9]", "8")));
        int w = Math.max(tw, SdfText.w(day)) + 18;
        int h = day.isEmpty() ? 15 : 26;
        lastW = w; lastH = h;
        int x1 = HudElement.clampX(x, w), y1 = HudElement.clampY(y, h);

        Look.panel(g, x1, y1, x1 + w, y1 + h, Look.ACCENT);
        SdfText.ui(g, time, x1 + 9, y1 + 4, Look.TEXT, false);
        if (!day.isEmpty()) SdfText.ui(g, day, x1 + 9, y1 + 15, Look.MUTED, false);
    }
}
