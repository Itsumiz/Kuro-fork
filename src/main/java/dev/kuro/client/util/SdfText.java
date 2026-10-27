package dev.kuro.client.util;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Shader text. Instead of vanilla's bitmap glyph atlas, text is drawn from a signed-distance-field atlas
 * (assets/kuro/textures/gui/font/sdf_*.png, built by tools/gen_sdf_font.py from the Instrument fonts) and the
 * fragment shader (shaders/core/ui_text.fsh) turns the distance into a perfectly anti-aliased edge at ANY size,
 * so text stays razor sharp when the menu is scaled, with no blur and no jagged stair-steps.
 *
 * Every glyph is one textured quad. Positions are computed in atlas texels (48 per em) and the matrix is scaled
 * down, so glyphs land on sub-pixel positions even though DrawContext only takes whole numbers.
 * Characters the atlas does not have are drawn as '?'.
 */
public final class SdfText {
    private SdfText() {}

    public enum Face { SANS, BOLD, SERIF, SERIF_ITALIC }

    /** Default em size in GUI px: the same size the old vanilla font JSONs used for the UI text. */
    public static final float BASE = 10f;
    /** Baseline distance below the top of a line, as a fraction of the em size (0.7 = 7 px at size 10, exactly where vanilla put the baseline, so existing layouts line up). */
    public static final float BASELINE = 0.7f;

    private static final class Glyph {
        int u, v, w, h, xo, yo;
        float adv;
    }

    private static final class FontData {
        Identifier tex;
        int texW, texH, em;
        final Map<Integer, Glyph> glyphs = new HashMap<>();
        final Map<Integer, Float> kern = new HashMap<>();
        Glyph fallback;
        boolean ok;
    }

    private static final FontData[] FONTS = new FontData[Face.values().length];

    static {
        FONTS[Face.SANS.ordinal()] = load("sans");
        FONTS[Face.BOLD.ordinal()] = load("bold");
        FONTS[Face.SERIF.ordinal()] = load("serif");
        FONTS[Face.SERIF_ITALIC.ordinal()] = load("serif_italic");
    }

    private static FontData load(String name) {
        FontData f = new FontData();
        f.tex = Identifier.fromNamespaceAndPath("kuro", "textures/gui/font/sdf_" + name + ".png");
        String path = "/assets/kuro/font/sdf_" + name + ".metrics";
        try (InputStream in = SdfText.class.getResourceAsStream(path)) {
            if (in == null) return f;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String[] t = line.trim().split("\\s+");
                if (t.length == 0) continue;
                switch (t[0]) {
                    case "font" -> { f.texW = Integer.parseInt(t[1]); f.texH = Integer.parseInt(t[2]); f.em = Integer.parseInt(t[3]); }
                    case "g" -> {
                        Glyph g = new Glyph();
                        int cp = Integer.parseInt(t[1]);
                        g.u = Integer.parseInt(t[2]); g.v = Integer.parseInt(t[3]);
                        g.w = Integer.parseInt(t[4]); g.h = Integer.parseInt(t[5]);
                        g.xo = Integer.parseInt(t[6]); g.yo = Integer.parseInt(t[7]);
                        g.adv = Float.parseFloat(t[8]);
                        f.glyphs.put(cp, g);
                    }
                    case "k" -> f.kern.put(Integer.parseInt(t[1]) << 16 | Integer.parseInt(t[2]), Float.parseFloat(t[3]));
                    default -> {}
                }
            }
            f.fallback = f.glyphs.get((int) '?');
            f.ok = f.texW > 0 && f.em > 0 && f.fallback != null;
        } catch (Exception e) {
            f.ok = false;
        }
        return f;
    }

    private static Glyph glyph(FontData f, int cp) {
        Glyph g = f.glyphs.get(cp);
        return g != null ? g : f.fallback;
    }

    /** Width of the text in GUI px at the given em size. */
    public static float width(Face face, String s, float size) {
        FontData f = FONTS[face.ordinal()];
        if (!f.ok || s.isEmpty()) return 0f;
        float pen = 0f;
        int prev = 0;
        for (int i = 0; i < s.length(); i++) {
            int cp = s.charAt(i);
            Glyph g = glyph(f, cp);
            if (g == null) continue;
            if (prev != 0) { Float k = f.kern.get(prev << 16 | cp); if (k != null) pen += k; }
            pen += g.adv;
            prev = cp;
        }
        return pen * size / f.em;
    }

    /** Draws the text with its top-left corner at (x, y) in the current matrix space. Returns the width drawn. */
    public static float draw(GuiGraphics g, Face face, String s, float x, float y, float size, int argb) {
        FontData f = FONTS[face.ordinal()];
        if (!f.ok || s.isEmpty() || (argb >>> 24) < 6) return width(face, s, size);   // vanilla treats near-zero alpha as opaque
        float k = size / f.em;
        var m = g.pose();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(k, k);
        float base = BASELINE * size / k;                       // baseline in texel units
        float pen = 0f;
        int prev = 0;
        for (int i = 0; i < s.length(); i++) {
            int cp = s.charAt(i);
            Glyph gl = glyph(f, cp);
            if (gl == null) continue;
            if (prev != 0) { Float kn = f.kern.get(prev << 16 | cp); if (kn != null) pen += kn; }
            if (gl.w > 0) {
                g.blit(ShaderUi.textPipeline(), f.tex, Math.round(pen) + gl.xo, Math.round(base) + gl.yo,
                        (float) gl.u, (float) gl.v, gl.w, gl.h, gl.w, gl.h, f.texW, f.texH, argb);
            }
            pen += gl.adv;
            prev = cp;
        }
        m.popMatrix();
        return pen * k;
    }

    public static float draw(GuiGraphics g, Face face, String s, float x, float y, int argb) {
        return draw(g, face, s, x, y, BASE, argb);
    }

    /** Draws the text centred on cx. */
    public static void centered(GuiGraphics g, Face face, String s, float cx, float y, float size, int argb) {
        draw(g, face, s, cx - width(face, s, size) / 2f, y, size, argb);
    }

    // ---- drop-in helpers for the plain-String UI code (replace TextRenderer.getWidth / drawText / trimToWidth) ----

    /** Width in whole GUI px of a UI string (regular face, default size). */
    public static int w(String s) { return (int) Math.ceil(width(Face.SANS, s, BASE)); }

    /** Draws a UI string like DrawContext.drawText(renderer, text, x, y, color, shadow) did, but with shader text. */
    public static void ui(GuiGraphics g, String s, float x, float y, int color, boolean shadow) {
        if (shadow) draw(g, Face.SANS, s, x + 1f, y + 1f, BASE, (color & 0xFF000000) | ((color & 0xFCFCFC) >> 2));
        draw(g, Face.SANS, s, x, y, BASE, color);
    }

    /** Cuts the string to fit max GUI px (like TextRenderer.trimToWidth); fromEnd keeps the end of the string instead. */
    public static String trim(String s, int max, boolean fromEnd) {
        if (w(s) <= max) return s;
        String t = s;
        while (!t.isEmpty() && w(t) > max) t = fromEnd ? t.substring(1) : t.substring(0, t.length() - 1);
        return t;
    }

    public static String trim(String s, int max) { return trim(s, max, false); }
}
