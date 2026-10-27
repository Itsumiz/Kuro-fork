package dev.kuro.client.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * The shared "prestige" look for every world overlay: smoked-dark rounded plates, a thin light edge and
 * a coloured accent bar, soft saturated accents, translucent fills with a bright outline. Keep new overlays on
 * these helpers so everything matches.
 */
public final class Look {
    private Look() {}

    public static int BG      = 0xD00D0F14;   // plate background (smoked dark); recoloured by apply()
    public static int EDGE    = 0x3CFFFFFF;   // 1px light edge; recoloured by apply()
    /** When true, overlays use ACCENT instead of their strength colours (red/orange/amber). */
    public static boolean accentOverride;
    public static int ACCENT = 0xFF8C7BFF;
    public static final int TEXT    = 0xFFF1F3F8;
    public static final int MUTED   = 0xFF8E95A3;
    public static final int TRACK   = 0x40FFFFFF;   // empty part of a bar

    public static final int GOOD    = 0xFF4ADE80;
    public static final int WARN    = 0xFFFFC857;
    public static final int ORANGE  = 0xFFFF9F43;
    public static final int BAD     = 0xFFFF5468;

    /**
     * Recolours every overlay plate. tint 0 = the default smoked-dark look; higher tints the plates and their edge
     * with the hue. opacity is 0.3..1. accentOnly makes highlights and tooltips use one accent colour.
     */
    public static void apply(float hue, float tint, float opacity, boolean accentOnly) {
        int a = Mth.clamp(Math.round(opacity * 255), 60, 255);
        int base = tint <= 0.001f ? 0x0D0F14 : Mth.hsvToRgb(hue, 0.35f + 0.45f * tint, 0.07f + 0.16f * tint) & 0xFFFFFF;
        BG = (a << 24) | base;
        EDGE = tint <= 0.001f ? 0x3CFFFFFF : (0x78 << 24) | (Mth.hsvToRgb(hue, 0.30f + 0.50f * tint, 1f) & 0xFFFFFF);
        ACCENT = 0xFF000000 | (Mth.hsvToRgb(hue, 0.62f, 1f) & 0xFFFFFF);
        accentOverride = accentOnly;
    }

    /** A pleasant, slightly softened colour for a hue (0..1). Returns 0xRRGGBB. */
    public static int rgb(float hue) { return Mth.hsvToRgb(hue, 0.78f, 1f) & 0xFFFFFF; }

    public static int withAlpha(int argb, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0xFFFFFF); }

    /** Colour for a 0..1 "how good" fraction: green, amber, red. */
    public static int health(float frac) { return frac > 0.6f ? GOOD : frac > 0.3f ? WARN : BAD; }

    // ---------------- plates ----------------

    /** Rectangle with rounded corners (radius comes from the HUD module). */
    public static void rrect(GuiGraphics g, int x1, int y1, int x2, int y2, int c) {
        Ui.round(g, x1, y1, x2, y2, c);
    }

    /** Dark glass plate: frosted shader body with rim light and a light edge. */
    public static void plate(GuiGraphics g, int x1, int y1, int x2, int y2) {
        Ui.panel(g, x1, y1, x2, y2, Ui.radius, BG, EDGE, 0, false);
    }

    /** Plate with a coloured accent bar on the left and a soft glow in that colour. */
    public static void panel(GuiGraphics g, int x1, int y1, int x2, int y2, int accent) {
        Ui.panel(g, x1, y1, x2, y2, Ui.radius, BG, EDGE, withAlpha(accent, 0x60), false);
        Ui.corners(g, x1 + 2, y1 + 3, x1 + 4, y2 - 3, 1, 0xFF000000 | (accent & 0xFFFFFF), true, true, true, true);
    }

    /** Small one-line label centred on cx with its top at y. Returns the plate width. */
    public static int tag(GuiGraphics g, Font tr, String text, int cx, int y, int accent) {
        int tw = SdfText.w(text), w = tw + 14;
        int x1 = cx - w / 2;
        panel(g, x1, y, x1 + w, y + 13, accent);
        SdfText.ui(g, text, x1 + 8, y + 3, TEXT, false);
        return w;
    }

    /** Same as tag(), but clamps the plate so it always stays on the screen. */
    public static void tagClamped(GuiGraphics g, Font tr, String text, int cx, int y, int accent) {
        int w = SdfText.w(text) + 14;
        int x = Mth.clamp(cx, w / 2 + 2, Math.max(w / 2 + 2, Projector.width() - w / 2 - 2));
        int yy = Mth.clamp(y, 2, Math.max(2, Projector.height() - 15));
        tag(g, tr, text, x, yy, accent);
    }

    /** Thin progress bar: rounded shader track and a gradient fill. */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, double frac, int color) {
        int r = Math.max(0, h / 2);
        Ui.corners(g, x, y, x + w, y + h, r, TRACK, true, true, true, true);
        int f = (int) Math.round(w * Mth.clamp(frac, 0.0, 1.0));
        if (f > 0) Ui.gradient(g, x, y, x + f, y + h, r, Ui.mix(0xFF000000 | (color & 0xFFFFFF), 0xFF000000, 0.25f), 0xFF000000 | (color & 0xFFFFFF));
    }
}
