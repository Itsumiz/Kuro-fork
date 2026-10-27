package dev.kuro.client.util;

import net.minecraft.client.gui.GuiGraphics;

public final class Gfx {
    private Gfx() {}

    private static final double[] SEG = new double[4];
    private static final double[][] EDGES = new double[12][4];
    private static final double[] BR = new double[4];
    private static final double[] CX = new double[8], CY = new double[8];

    public static void outline(GuiGraphics g, int x1, int y1, int x2, int y2, int t, int c) {
        g.fill(x1, y1, x2, y1 + t, c);
        g.fill(x1, y2 - t, x2, y2, c);
        g.fill(x1, y1 + t, x1 + t, y2 - t, c);
        g.fill(x2 - t, y1 + t, x2, y2 - t, c);
    }

    /** Draws a 2D ESP box from a Projector.bounds() result. fill == 0 means no fill. */
    public static void box(GuiGraphics g, double[] r, int thickness, int line, int fill) {
        int x1 = (int) Math.floor(r[0]), y1 = (int) Math.floor(r[1]);
        int x2 = (int) Math.ceil(r[2]), y2 = (int) Math.ceil(r[3]);
        int w = Projector.width(), h = Projector.height();
        if (x2 < 0 || y2 < 0 || x1 > w || y1 > h) return;
        if (x2 - x1 > w * 3 || y2 - y1 > h * 3) return;
        if (x2 - x1 < 4) { int c = (x1 + x2) / 2; x1 = c - 2; x2 = c + 2; }
        if (y2 - y1 < 4) { int c = (y1 + y2) / 2; y1 = c - 2; y2 = c + 2; }
        if (fill != 0) g.fill(x1, y1, x2, y2, fill);
        outline(g, x1, y1, x2, y2, thickness, line);
    }

    /** Fills a convex screen-space polygon (already projected, see Projector.polygon) with one rectangle per scanline. */
    public static void fillPolygon(GuiGraphics g, double[] xs, double[] ys, int n, int color) {
        if (n < 3) return;
        for (int i = 0; i < n; i++) if (!Double.isFinite(xs[i]) || !Double.isFinite(ys[i])) return;
        Raster.fillHull((ax, ay, bx, by) -> g.fill(ax, ay, bx, by, color), xs, ys, n, Projector.width(), Projector.height());
    }

    /** Kept for callers: lines are shader quads now, so there is no per-frame rectangle budget any more. */
    public static void beginFrame() {}

    /**
     * Screen-space line, clipped to the screen. One anti-aliased shader quad (ShaderUi.line) with round caps and a faint
     * glow, instead of a staircase of rectangles, so it is smooth at any GUI scale and costs one quad.
     */
    public static void line(GuiGraphics g, double x1, double y1, double x2, double y2, int thickness, int color) {
        if (!(Double.isFinite(x1) && Double.isFinite(y1) && Double.isFinite(x2) && Double.isFinite(y2))) return;
        // Liang-Barsky clip to the screen (plus a margin) so lines that run far off screen stay cheap
        double m = 8, xmin = -m, ymin = -m, xmax = Projector.width() + m, ymax = Projector.height() + m;
        double dx = x2 - x1, dy = y2 - y1, t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy}, q = {x1 - xmin, xmax - x1, y1 - ymin, ymax - y1};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return; }
            else {
                double r = q[i] / p[i];
                if (p[i] < 0) { if (r > t1) return; if (r > t0) t0 = r; }
                else { if (r < t0) return; if (r < t1) t1 = r; }
            }
        }
        ShaderUi.line(g, x1 + t0 * dx, y1 + t0 * dy, x1 + t1 * dx, y1 + t1 * dy, Math.max(0.8f, thickness * 0.9f), color);
    }

    /**
     * Full 3D wireframe cube: all 12 edges of the world-space box, projected with the real
     * camera, so it looks like a proper block outline from any angle. A non-zero fill colour
     * also shades the cube's silhouette (only when the whole cube is in front of the camera).
     */
    public static void cube(GuiGraphics g, double x1, double y1, double z1, double x2, double y2, double z2,
                            int thickness, int line, int fill) {
        cube(g, x1, y1, z1, x2, y2, z2, thickness, line, fill, false);
    }

    /** Same as above; glow = soft halo around every edge (only on cubes big enough on screen to afford it). */
    public static void cube(GuiGraphics g, double x1, double y1, double z1, double x2, double y2, double z2,
                            int thickness, int line, int fill, boolean glow) {
        int w = Projector.width(), h = Projector.height();

        // Level of detail: every filled rectangle is real GPU work, so small/far cubes are simplified.
        double ext = Math.max(x2 - x1, Math.max(y2 - y1, z2 - z1));
        double depth = Projector.depth((x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2);
        if (depth > 0.5) {
            double px = Projector.pixelsPerUnit(depth) * ext;
            if (px < 40) glow = false;                      // halo is 3x the line work: only worth it up close
            if (px < 8) {                                   // speck: a single dot
                if (Projector.project((x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2, SEG)
                        && SEG[0] > -2 && SEG[1] > -2 && SEG[0] < w + 2 && SEG[1] < h + 2) {
                    int cx = (int) SEG[0], cy = (int) SEG[1];
                    g.fill(cx - 1, cy - 1, cx + 2, cy + 2, line);
                }
                return;
            }
            if (px < 28) {                                  // small: flat box (a handful of rectangles)
                if (Projector.bounds(x1, y1, z1, x2, y2, z2, BR)) box(g, BR, 1, line, fill);
                return;
            }
        }

        if (fill != 0) {
            boolean allFront = true;
            for (int i = 0; i < 8 && allFront; i++) {
                double[] o = SEG;
                if (!Projector.project((i & 1) == 0 ? x1 : x2, (i & 2) == 0 ? y1 : y2, (i & 4) == 0 ? z1 : z2, o)) allFront = false;
                else { CX[i] = o[0]; CY[i] = o[1]; }
            }
            if (allFront) Raster.fillHull((ax, ay, bx, by) -> g.fill(ax, ay, bx, by, fill), CX, CY, 8, w, h);
        }

        int n = 0;
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) != 0) continue;
                int j = i | bit;
                if (!Projector.segment(
                        (i & 1) == 0 ? x1 : x2, (i & 2) == 0 ? y1 : y2, (i & 4) == 0 ? z1 : z2,
                        (j & 1) == 0 ? x1 : x2, (j & 2) == 0 ? y1 : y2, (j & 4) == 0 ? z1 : z2, EDGES[n])) continue;
                n++;
            }
        }
        if (glow) {
            int outer = (40 << 24) | (line & 0xFFFFFF), inner = (110 << 24) | (line & 0xFFFFFF);
            for (int k = 0; k < n; k++) line(g, EDGES[k][0], EDGES[k][1], EDGES[k][2], EDGES[k][3], thickness + 4, outer);
            for (int k = 0; k < n; k++) line(g, EDGES[k][0], EDGES[k][1], EDGES[k][2], EDGES[k][3], thickness + 2, inner);
        }
        for (int k = 0; k < n; k++) line(g, EDGES[k][0], EDGES[k][1], EDGES[k][2], EDGES[k][3], thickness, line);
    }
}
