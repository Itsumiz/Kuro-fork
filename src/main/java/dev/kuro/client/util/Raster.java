package dev.kuro.client.util;

/**
 * Pure 2D rasterising helpers (no Minecraft types) that emit axis-aligned rectangles.
 * Lines are emitted as horizontal/vertical runs, so a line costs about min(|dx|,|dy|)+1
 * rectangles instead of one per pixel.
 */
public final class Raster {
    private Raster() {}

    public interface Sink { void rect(int x1, int y1, int x2, int y2); } // x2/y2 exclusive

    /** Line from (x1,y1) to (x2,y2), clipped to [0,w]x[0,h], thickness t pixels. */
    public static void line(Sink out, double x1, double y1, double x2, double y2, int t, int w, int h) {
        Sink s = (ax, ay, bx, by) -> {   // keep every rectangle inside the screen
            ax = Math.max(0, ax); ay = Math.max(0, ay); bx = Math.min(w, bx); by = Math.min(h, by);
            if (bx > ax && by > ay) out.rect(ax, ay, bx, by);
        };
        if (!(Double.isFinite(x1) && Double.isFinite(y1) && Double.isFinite(x2) && Double.isFinite(y2))) return;
        double dx = x2 - x1, dy = y2 - y1, t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1, (w - 1) - x1, y1, (h - 1) - y1};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) { if (r > t1) return; if (r > t0) t0 = r; }
                else          { if (r < t0) return; if (r < t1) t1 = r; }
            }
        }
        int ax = (int) Math.floor(x1 + t0 * dx), ay = (int) Math.floor(y1 + t0 * dy);
        int bx = (int) Math.floor(x1 + t1 * dx), by = (int) Math.floor(y1 + t1 * dy);
        t = Math.max(1, t);
        int half = (t - 1) / 2;

        int adx = Math.abs(bx - ax), ady = Math.abs(by - ay);
        int sx = bx >= ax ? 1 : -1, sy = by >= ay ? 1 : -1;
        if (adx >= ady) {
            for (int k = 0; k <= ady; k++) {
                double ta = ady == 0 ? 0 : Math.max(0, (k - 0.5) / ady);
                double tb = ady == 0 ? 1 : Math.min(1, (k + 0.5) / ady);
                int xa = ax + (int) Math.round(sx * adx * ta), xb = ax + (int) Math.round(sx * adx * tb);
                int y = ay + sy * k - half;
                s.rect(Math.min(xa, xb), y, Math.max(xa, xb) + 1, y + t);
            }
        } else {
            for (int k = 0; k <= adx; k++) {
                double ta = Math.max(0, (k - 0.5) / adx);
                double tb = Math.min(1, (k + 0.5) / adx);
                int ya = ay + (int) Math.round(sy * ady * ta), yb = ay + (int) Math.round(sy * ady * tb);
                int x = ax + sx * k - half;
                s.rect(x, Math.min(ya, yb), x + t, Math.max(ya, yb) + 1);
            }
        }
    }

    /** Fills the convex hull of the n points (xs[i], ys[i]) with one rectangle per scanline. */
    public static void fillHull(Sink s, double[] xs, double[] ys, int n, int w, int h) {
        // Andrew's monotone chain on index order sorted by x then y
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {              // insertion sort by x then y (n is tiny, no boxing)
            int j = i;
            while (j > 0 && (xs[idx[j - 1]] > xs[i] || (xs[idx[j - 1]] == xs[i] && ys[idx[j - 1]] > ys[i]))) { idx[j] = idx[j - 1]; j--; }
            idx[j] = i;
        }
        int[] hull = new int[2 * n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(xs, ys, hull[k - 2], hull[k - 1], idx[i]) <= 0) k--;
            hull[k++] = idx[i];
        }
        for (int i = n - 2, lo = k + 1; i >= 0; i--) {
            while (k >= lo && cross(xs, ys, hull[k - 2], hull[k - 1], idx[i]) <= 0) k--;
            hull[k++] = idx[i];
        }
        k--; // last point repeats the first
        if (k < 3) return;

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < k; i++) { minY = Math.min(minY, ys[hull[i]]); maxY = Math.max(maxY, ys[hull[i]]); }
        int y0 = Math.max(0, (int) Math.ceil(minY)), y1 = Math.min(h - 1, (int) Math.floor(maxY));
        for (int y = y0; y <= y1; y++) {
            double lx = Double.MAX_VALUE, rx = -Double.MAX_VALUE;
            double yc = y + 0.5;
            for (int i = 0; i < k; i++) {
                int a = hull[i], b = hull[(i + 1) % k];
                double ya = ys[a], yb = ys[b];
                if ((ya <= yc && yb > yc) || (yb <= yc && ya > yc)) {
                    double x = xs[a] + (yc - ya) / (yb - ya) * (xs[b] - xs[a]);
                    lx = Math.min(lx, x); rx = Math.max(rx, x);
                }
            }
            if (lx > rx) continue;
            int xa = Math.max(0, (int) Math.round(lx)), xb = Math.min(w, (int) Math.round(rx));   // round (not floor/ceil) so the fill never pokes past the outline
            if (xb > xa) s.rect(xa, y, xb, y + 1);
        }
    }

    private static double cross(double[] xs, double[] ys, int o, int a, int b) {
        return (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o]);
    }
}
