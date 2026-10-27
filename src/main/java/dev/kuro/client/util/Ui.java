package dev.kuro.client.util;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Rounded-shape drawing shared by every menu and overlay. Shapes are drawn by GPU shaders (see ShaderUi): one quad
 * per shape, smooth at any GUI scale, instead of rows of 1-pixel fills. The radius is set from the HUD module.
 */
public final class Ui {
    private Ui() {}

    /** Corner radius in GUI pixels (0 = square). Set by the HUD module. */
    public static int radius = 4;

    /** Pixels cut off the edge on the row that is d rows in from the top/bottom edge of a corner with radius r. */
    private static int inset(int d, int r) {
        if (d >= r) return 0;
        double dy = r - d - 0.5;
        return (int) Math.round(r - Math.sqrt(r * (double) r - dy * dy));
    }

    /** Same as the private corner profile, for shapes drawn column by column (gradients). */
    public static int cornerInset(int d, int r) { return inset(d, r); }

    /** Linear blend of two ARGB colours (t = 0 gives a, t = 1 gives b). */
    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24), ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24), br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /**
     * Rounded rectangle filled with a left-to-right gradient, on the GPU: the left colour fades out while the right
     * colour fades in (two shader quads, no per-column fills).
     */
    public static void gradient(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int left, int right) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (left == right) { ShaderUi.shape(g, ShaderUi.Mode.FLAT, x1, y1, x2, y2, r, left); return; }
        ShaderUi.shape(g, ShaderUi.Mode.RAMP_DOWN, x1, y1, x2, y2, r, left);
        ShaderUi.shape(g, ShaderUi.Mode.RAMP_UP, x1, y1, x2, y2, r, right);
    }

    /** Rainbow hue strip (shader), rounded with radius r. */
    public static void hueStrip(GuiGraphics g, int x1, int y1, int x2, int y2, int r) {
        ShaderUi.shape(g, ShaderUi.Mode.HUE, x1, y1, x2, y2, Math.min(r, Math.min(x2 - x1, y2 - y1) / 2), 0xFFFFFFFF);
    }

    /** Colour wheel (shader): hue around the circle, saturation from the centre out, at the given brightness (0..1). */
    public static void wheel(GuiGraphics g, int x1, int y1, int x2, int y2, float brightness) {
        ShaderUi.shape(g, ShaderUi.Mode.WHEEL, x1, y1, x2, y2, Math.round(Math.max(0f, Math.min(1f, brightness)) * 63f), 0xFFFFFFFF);
    }

    /**
     * A full window / plate: soft halo, frosted gradient body with an inner rim light, and a thin edge, all drawn by
     * shaders. glow is the halo colour (ARGB, its alpha is scaled by HUD -> Glow); pass 0 for none.
     */
    public static void panel(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int fill, int edge, int glow) {
        panel(g, x1, y1, x2, y2, r, fill, edge, glow, true);
    }

    /** Same as above; allowBlob = false keeps the plain rounded rectangle even when HUD -> Blob shape is on (used by the overlays). */
    public static void panel(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int fill, int edge, int glow, boolean allowBlob) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        boolean blob = allowBlob && ShaderUi.blob && w >= 40 && h >= 24;     // organic edge only on big shapes
        float gs = ShaderUi.glow;
        if (gs > 0.02f && (glow >>> 24) != 0 && w >= 24 && h >= 14) {
            int a = Math.min(255, Math.round((glow >>> 24) * gs / 0.6f));
            ShaderUi.halo(g, x1, y1, x2, y2, r, 6 + Math.round(8 * gs), (a << 24) | (glow & 0xFFFFFF), blob);
        }
        ShaderUi.shape(g, ShaderUi.Mode.PANEL, x1, y1, x2, y2, r, fill, blob);
        ShaderUi.shape(g, ShaderUi.Mode.RING, x1, y1, x2, y2, r, edge, blob);
    }

    /** Opacity of the enabled-module pill: 40% opaque = 60% transparent. */
    public static final int SELECTED_ALPHA = 0x66;

    /**
     * A selected / enabled row (e.g. an enabled module): one flat rounded shape in the given colour, 60% transparent.
     * No glow, no gradient, no outline, no animation. (seed / name overloads are kept so callers did not need to change.)
     */
    public static void selected(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int fill, int seed) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0 || (fill >>> 24) == 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        ShaderUi.shape(g, ShaderUi.Mode.FLAT, x1, y1, x2, y2, r, (SELECTED_ALPHA << 24) | (fill & 0xFFFFFF));
    }

    public static void selected(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int fill, String name) {
        selected(g, x1, y1, x2, y2, r, fill, 0);
    }

    public static void selected(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int fill) {
        selected(g, x1, y1, x2, y2, r, fill, 0);
    }

    /** Filled rectangle, all four corners rounded with the global radius. */
    public static void round(GuiGraphics g, int x1, int y1, int x2, int y2, int c) {
        corners(g, x1, y1, x2, y2, radius, c, true, true, true, true);
    }

    /**
     * Filled rectangle where each corner can be rounded or left square (order: top-left, top-right, bottom-right, bottom-left).
     * Drawn by the shape shader: one quad. Square corners on one side are done by drawing a taller shape and clipping it.
     */
    public static void corners(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int c,
                               boolean tl, boolean tr, boolean br, boolean bl) {
        int w = x2 - x1, h = y2 - y1;
        if (w <= 0 || h <= 0 || (c >>> 24) == 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r <= 0 || (!tl && !tr && !br && !bl)) { g.fill(x1, y1, x2, y2, c); return; }
        if (tl && tr && br && bl) { ShaderUi.shape(g, ShaderUi.Mode.FLAT, x1, y1, x2, y2, r, c); return; }
        if (tl == tr && bl == br) {            // top pair / bottom pair differ: stretch the shape past the square side and clip
            g.enableScissor(x1, y1, x2, y2);
            ShaderUi.shape(g, ShaderUi.Mode.FLAT, x1, tl ? y1 : y1 - r, x2, bl ? y2 : y2 + r, r, c);
            g.disableScissor();
            return;
        }
        if (tl == bl && tr == br) {            // left pair / right pair differ
            g.enableScissor(x1, y1, x2, y2);
            ShaderUi.shape(g, ShaderUi.Mode.FLAT, tl ? x1 : x1 - r, y1, tr ? x2 : x2 + r, y2, r, c);
            g.disableScissor();
            return;
        }
        cornersLegacy(g, x1, y1, x2, y2, r, c, tl, tr, br, bl);   // odd mask (e.g. a single rounded corner)
    }

    /** The old CPU path: one rectangle per row of each corner. Only used for odd corner masks. */
    private static void cornersLegacy(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int c,
                                      boolean tl, boolean tr, boolean br, boolean bl) {
        int h = y2 - y1;
        for (int d = 0; d < r; d++) {
            int o = inset(d, r);
            g.fill(x1 + (tl ? o : 0), y1 + d, x2 - (tr ? o : 0), y1 + d + 1, c);
            g.fill(x1 + (bl ? o : 0), y2 - 1 - d, x2 - (br ? o : 0), y2 - d, c);
        }
        if (h - 2 * r > 0) g.fill(x1, y1 + r, x2, y2 - r, c);
    }

    /** Thin rounded ring with an explicit corner radius (use the same radius as the shape it surrounds). */
    public static void ring(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int c) {
        int w = x2 - x1, h = y2 - y1;
        if (w < 2 || h < 2 || (c >>> 24) == 0) return;
        ShaderUi.shape(g, ShaderUi.Mode.RING, x1, y1, x2, y2, Math.min(r, Math.min(w, h) / 2), c);
    }

    /** 1px outline, all four corners rounded with the global radius. */
    public static void outline(GuiGraphics g, int x1, int y1, int x2, int y2, int c) {
        outline(g, x1, y1, x2, y2, c, true, true, true, true);
    }

    /** 1px outline that follows the same corner shape as corners(). All-rounded outlines are a single ring shader quad. */
    public static void outline(GuiGraphics g, int x1, int y1, int x2, int y2, int c,
                               boolean tl, boolean tr, boolean br, boolean bl) {
        int w = x2 - x1, h = y2 - y1;
        if (w < 2 || h < 2 || (c >>> 24) == 0) return;
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (tl && tr && br && bl) { ShaderUi.shape(g, ShaderUi.Mode.RING, x1, y1, x2, y2, r, c); return; }
        outlineLegacy(g, x1, y1, x2, y2, c, tl, tr, br, bl);
    }

    private static void outlineLegacy(GuiGraphics g, int x1, int y1, int x2, int y2, int c,
                                      boolean tl, boolean tr, boolean br, boolean bl) {
        int w = x2 - x1, h = y2 - y1;
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 1) { Gfx.outline(g, x1, y1, x2, y2, 1, c); return; }
        int ri = r - 1;                                   // the inner edge of the ring has a radius one smaller
        for (int d = 0; d < r; d++) {
            ring(g, x1, x2, y1 + d, d, r, ri, tl, tr, c);
            ring(g, x1, x2, y2 - 1 - d, d, r, ri, bl, br, c);
        }
        if (h - 2 * r > 0) {
            g.fill(x1, y1 + r, x1 + 1, y2 - r, c);
            g.fill(x2 - 1, y1 + r, x2, y2 - r, c);
        }
    }

    private static void ring(GuiGraphics g, int x1, int x2, int y, int d, int r, int ri,
                             boolean roundL, boolean roundR, int c) {
        int oL = roundL ? inset(d, r) : 0, oR = roundR ? inset(d, r) : 0;
        if (d == 0) { g.fill(x1 + oL, y, x2 - oR, y + 1, c); return; }   // top/bottom edge: one full-width line
        int iL = 1 + (roundL ? inset(d - 1, ri) : 0), iR = 1 + (roundR ? inset(d - 1, ri) : 0);
        g.fill(x1 + oL, y, x1 + Math.max(oL + 1, iL), y + 1, c);
        g.fill(x2 - Math.max(oR + 1, iR), y, x2 - oR, y + 1, c);
    }
}
