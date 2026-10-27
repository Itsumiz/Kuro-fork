package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.util.Mth;

/**
 * Replaces the sky with a flat colour (white by default, gray, or any tint from the colour strip) and makes the
 * clouds white. The actual colour swap happens in SkyRenderingMixin / CloudRendererMixin / FogRendererMixin,
 * which ask this module for the colours.
 */
public class SkyModule extends Module {
    private static SkyModule instance;

    private final Setting.Bool gray = add(new Setting.Bool("Gray sky", false));
    private final Setting.Num grayLevel = add(new Setting.Num("Gray brightness %", 55, 15, 95, 5));
    private final Setting.Hue tint = add(new Setting.Hue("Sky color", 0.6f));
    private final Setting.Num tintAmount = add(new Setting.Num("Sky color amount %", 0, 0, 100, 5));   // 0 = plain white / gray
    private final Setting.Bool rainbow = add(new Setting.Bool("Rainbow sky", false));              // the sky colour slowly cycles through every hue
    private final Setting.Num rainbowSpeed = add(new Setting.Num("Rainbow speed", 1.0, 0.1, 5.0, 0.1));
    private final Setting.Bool whiteClouds = add(new Setting.Bool("White clouds", true));         // clouds white, or tinted with the colour below
    private final Setting.Hue cloudTint = add(new Setting.Hue("Cloud color", 0.9f));
    private final Setting.Num cloudAmount = add(new Setting.Num("Cloud color amount %", 0, 0, 100, 5));   // 0 = plain white
    private final Setting.Bool fog = add(new Setting.Bool("Match fog", true));
    private final Setting.Bool ownFog = add(new Setting.Bool("Separate fog color", false));       // fog / horizon gets its own colour instead of the sky's
    private final Setting.Hue fogTint = add(new Setting.Hue("Fog color", 0.6f));
    private final Setting.Num fogAmount = add(new Setting.Num("Fog color amount %", 60, 0, 100, 5));
    private final Setting.Bool glow = add(new Setting.Bool("Hide sunset glow", true));
    private final Setting.Bool glowColor = add(new Setting.Bool("Custom sunset glow color", false));   // when the glow is not hidden: recolour it
    private final Setting.Hue glowTint = add(new Setting.Hue("Sunset glow color", 0.85f));

    public SkyModule() {
        super("Sky", Category.RENDER);
        instance = this;
    }

    public static SkyModule get() { return instance; }

    public static boolean active() { return instance != null && instance.isEnabled(); }

    /** Sky colour as 0xRRGGBB: white (or gray), optionally tinted with the chosen hue. */
    public int skyRgb() {
        float v = gray.value ? (float) (grayLevel.value / 100.0) : 1f;
        if (rainbow.value) {
            float h = (float) (((System.currentTimeMillis() / 1000.0) * 0.05 * rainbowSpeed.value) % 1.0);
            return Mth.hsvToRgb(h, 0.55f, Math.max(0.6f, v)) & 0xFFFFFF;     // soft pastel so it stays easy on the eyes
        }
        float s = (float) (tintAmount.value / 100.0);
        return Mth.hsvToRgb(tint.hue, s, v) & 0xFFFFFF;
    }

    /** Cloud colour as 0xRRGGBB: white, tinted by "Cloud color amount". */
    public int cloudRgb() { return Mth.hsvToRgb(cloudTint.hue, (float) (cloudAmount.value / 100.0), 1f) & 0xFFFFFF; }

    /** Fog / horizon colour as 0xRRGGBB: the sky's, or its own colour with "Separate fog color". */
    public int fogRgb() {
        if (!ownFog.value) return skyRgb();
        return Mth.hsvToRgb(fogTint.hue, (float) (fogAmount.value / 100.0), gray.value ? (float) (grayLevel.value / 100.0) : 1f) & 0xFFFFFF;
    }

    /** New colour for the sunrise / sunset band (its alpha is kept: it fades in and out with the time of day). */
    public int glowRgb() { return Mth.hsvToRgb(glowTint.hue, 0.8f, 1f) & 0xFFFFFF; }
    public boolean customGlow() { return !glow.value && glowColor.value; }

    public boolean cloudsOn() { return whiteClouds.value; }
    public boolean fogOn() { return fog.value; }
    public boolean noGlow() { return glow.value; }
}
