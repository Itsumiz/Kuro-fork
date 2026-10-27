package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/**
 * Projects world positions onto the GUI-scaled screen using the live camera,
 * so ESP/tags are drawn in the HUD layer (no custom render pipelines needed).
 */
public final class Projector {
    public static Vec3 camPos = Vec3.ZERO;
    public static float tickProgress;

    private static double fx, fy, fz, rx, rz, ux, uy, uz, tanHalf, aspect, sw, sh;
    private static final double[] TMP = new double[2];

    private Projector() {}

    /** Horizontal part of the camera's right vector (world x / z), for sideways drift that always looks sideways on screen. */
    public static double rightX() { return rx; }
    public static double rightZ() { return rz; }

    public static int width() { return (int) sw; }
    public static int height() { return (int) sh; }
    /** Real framebuffer pixels per GUI pixel (the GUI scale). */
    public static float guiScale = 1f;

    public static boolean begin(Minecraft mc) {
        Camera cam = mc.gameRenderer.getMainCamera();
        if (cam == null || !cam.isInitialized()) return false;
        camPos = cam.position();
        tickProgress = cam.getPartialTickTime();

        double yaw = Math.toRadians(cam.yRot());
        double pitch = Math.toRadians(cam.xRot());
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        fx = -sy * cp; fy = -sp; fz = cy * cp;      // forward
        rx = -cy;      rz = -sy;                    // right
        ux = -sy * sp; uy = cp; uz = cy * sp;       // up

        tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0) / dev.kuro.client.module.impl.Zoom.factor();
        sw = mc.getWindow().getGuiScaledWidth();
        sh = mc.getWindow().getGuiScaledHeight();
        aspect = sw / sh;
        guiScale = (float) mc.getWindow().getGuiScale();
        return true;
    }

    /** @return false if the point is behind the camera. out = {screenX, screenY}. */
    public static boolean project(double x, double y, double z, double[] out) {
        double dx = x - camPos.x, dy = y - camPos.y, dz = z - camPos.z;
        double zc = dx * fx + dy * fy + dz * fz;
        if (zc < 0.05) return false;
        double xc = dx * rx + dz * rz;
        double yc = dx * ux + dy * uy + dz * uz;
        out[0] = (xc / (zc * tanHalf * aspect) * 0.5 + 0.5) * sw;
        out[1] = (0.5 - yc / (zc * tanHalf) * 0.5) * sh;
        return true;
    }

    /** Distance of a world point in front of the camera (negative = behind). */
    public static double depth(double x, double y, double z) {
        return (x - camPos.x) * fx + (y - camPos.y) * fy + (z - camPos.z) * fz;
    }

    /** On-screen pixels covered by one world unit at the given depth. */
    public static double pixelsPerUnit(double depth) { return sh / (2 * tanHalf * depth); }

    private static final double NEAR = 0.06; // a little above project()'s 0.05 cutoff

    /**
     * Projects a world-space line segment, clipping it to the near plane first so edges that
     * pass behind the camera still draw correctly. out = {x1, y1, x2, y2}.
     * @return false if the whole segment is behind the camera.
     */
    public static boolean segment(double ax, double ay, double az, double bx, double by, double bz, double[] out) {
        double da = depth(ax, ay, az), db = depth(bx, by, bz);
        if (da < NEAR && db < NEAR) return false;
        if (da < NEAR) {
            double t = (NEAR - da) / (db - da);
            ax += (bx - ax) * t; ay += (by - ay) * t; az += (bz - az) * t;
        } else if (db < NEAR) {
            double t = (NEAR - db) / (da - db);
            bx += (ax - bx) * t; by += (ay - by) * t; bz += (az - bz) * t;
        }
        if (!project(ax, ay, az, TMP)) return false;
        out[0] = TMP[0]; out[1] = TMP[1];
        if (!project(bx, by, bz, TMP)) return false;
        out[2] = TMP[0]; out[3] = TMP[1];
        return true;
    }

    private static final double[] PX = new double[16], PY = new double[16], PZ = new double[16];

    /**
     * Projects a world-space convex polygon, clipping it against the near plane first so a polygon that
     * partly passes behind the camera (e.g. the chunk you stand on) still gets a correct, filled shape.
     * sx/sy must hold at least 2n entries.
     * @return the number of screen points written (0 if the polygon is entirely behind the camera).
     */
    public static int polygon(double[] wx, double[] wy, double[] wz, int n, double[] sx, double[] sy) {
        int m = 0;
        for (int i = 0; i < n && m < 14; i++) {
            int j = (i + 1) % n;
            double da = depth(wx[i], wy[i], wz[i]) - NEAR, db = depth(wx[j], wy[j], wz[j]) - NEAR;
            if (da >= 0) { PX[m] = wx[i]; PY[m] = wy[i]; PZ[m] = wz[i]; m++; }
            if ((da >= 0) != (db >= 0)) {
                double t = da / (da - db);
                PX[m] = wx[i] + (wx[j] - wx[i]) * t;
                PY[m] = wy[i] + (wy[j] - wy[i]) * t;
                PZ[m] = wz[i] + (wz[j] - wz[i]) * t;
                m++;
            }
        }
        int k = 0;
        for (int i = 0; i < m; i++) {
            if (!project(PX[i], PY[i], PZ[i], TMP)) continue;
            sx[k] = TMP[0]; sy[k] = TMP[1]; k++;
        }
        return k;
    }

    /**
     * Like project(), but a point behind the camera is pushed far off-screen in the direction
     * it lies relative to the view, so a tracer still points the right way.
     * out = {screenX, screenY}.
     */
    public static void projectOrPoint(double x, double y, double z, double[] out) {
        if (project(x, y, z, out)) return;
        double dx = x - camPos.x, dy = y - camPos.y, dz = z - camPos.z;
        double xc = dx * rx + dz * rz;
        double yc = dx * ux + dy * uy + dz * uz;
        double len = Math.hypot(xc, yc);
        if (len < 1e-6) { xc = 0; yc = -1; len = 1; }   // directly behind: point down
        double far = (sw + sh) * 2;
        out[0] = sw / 2 + xc / len * far;
        out[1] = sh / 2 - yc / len * far;
    }

    /** Unit view direction of the camera (the crosshair ray). out = {x, y, z}. */
    public static void forward(double[] out) { out[0] = fx; out[1] = fy; out[2] = fz; }

    /**
     * Ray (camera, view direction) against a world box. Returns the entry distance along the ray
     * (0 when the camera is inside the box), or -1 when the ray misses it.
     */
    public static double rayBox(double x1, double y1, double z1, double x2, double y2, double z2, double maxDist) {
        double t0 = 0, t1 = maxDist;
        double[] o = {camPos.x, camPos.y, camPos.z}, d = {fx, fy, fz};
        double[] lo = {x1, y1, z1}, hi = {x2, y2, z2};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) { if (o[i] < lo[i] || o[i] > hi[i]) return -1; continue; }
            double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
            if (a > b) { double t = a; a = b; b = t; }
            t0 = Math.max(t0, a); t1 = Math.min(t1, b);
            if (t0 > t1) return -1;
        }
        return t0;
    }

    /** Screen-space bounding rectangle of a world box. out = {minX, minY, maxX, maxY}. */
    public static boolean bounds(double x1, double y1, double z1, double x2, double y2, double z2, double[] out) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            if (!project((i & 1) == 0 ? x1 : x2, (i & 2) == 0 ? y1 : y2, (i & 4) == 0 ? z1 : z2, TMP)) return false;
            minX = Math.min(minX, TMP[0]); maxX = Math.max(maxX, TMP[0]);
            minY = Math.min(minY, TMP[1]); maxY = Math.max(maxY, TMP[1]);
        }
        out[0] = minX; out[1] = minY; out[2] = maxX; out[3] = maxY;
        return true;
    }
}
