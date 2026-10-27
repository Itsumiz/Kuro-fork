package dev.kuro.client.knife;

/** Voxel models. Only the karambit is ported so far. */
final class KnifeModels {
    /**
     * The karambit is built at one voxel per unit: chunky, like the reference model (the blade edge shows big steps).
     * Mesh units: x runs along the handle (the finger ring at the negative end), y is the spine side, z is the thickness.
     */
    static final int KARAMBIT_DETAIL = 1;
    static final float KARAMBIT_RING_X = -22.0f;
    static final float KARAMBIT_RING_Y = 0.0f;
    /** The blade was far too small next to the handle: it is now 1.75x longer and 2.5x wider (same curve, just scaled). */
    static final float KARAMBIT_BLADE_SCALE = 1.75f;
    static final float KARAMBIT_BLADE_WIDTH = 2.5f;
    static final float KARAMBIT_BLADE_LENGTH = 17.0f * KARAMBIT_BLADE_SCALE;
    /**
     * How far the blade root reaches back into the handle (voxels). The root used to start exactly at the handle's end and left
     * a one-voxel gap on the rows where the handle is narrower than the blade; the overlap closes it (handle voxels win).
     */
    static final float KARAMBIT_ROOT_OVERLAP = 1.6f;
    /** Where the hand holds the handle at rest (the middle of the handle, ~11 voxels from the ring). */
    static final float KARAMBIT_HOLD_X = KARAMBIT_RING_X + 10.0f;
    /** Where the blade starts (the handle's lower end), in mesh voxels. */
    static final int KARAMBIT_BLADE_X = -2, KARAMBIT_BLADE_Y = 7;
    /**
     * Handle outline traced from the reference screenshot (x = -21.., y = 0.. downwards, '#' = filled): the stepped, diagonal
     * band of black scales. Voxel (x, y) of row r, column c is x = -21 + c, y = r.
     */
    private static final String[] HANDLE = {
            "...#...............",
            "..###..............",
            ".####..............",
            "#########..........",
            "##########.....##..",
            "#################..",
            "...###############.",
            ".....#############.",
            ".....##############",
            ".........#########.",
            ".........#########.",
            "...............##.."};
    private static final int HANDLE_X0 = -21;

    private KnifeModels() {}

    /** Stable per-voxel brightness noise, 0.92..1.0. */
    static float grain(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13;
        h *= 1540483477;
        h ^= h >>> 15;
        return 0.92f + 0.08f * ((float) (h & 0x3FF) / 1023.0f);
    }

    /** Point and heading along the blade's centre line, s = distance from the handle end. The blade turns up to ~80 degrees. */
    private static float[] karambitCurve(float s) {
        float u = s / KARAMBIT_BLADE_SCALE;               // the curve shape is defined for the old 17-voxel blade, then scaled up
        float x = 0.0f, y = 0.0f, step = 0.05f;
        for (float t = 0.0f; t < u; t += step) {
            float heading = -(0.026f * t + 0.0042f * t * t);
            x += (float) Math.cos(heading) * step;
            y += (float) Math.sin(heading) * step;
        }
        float heading = -(0.026f * u + 0.0042f * u * u);
        return new float[]{x * KARAMBIT_BLADE_SCALE, y * KARAMBIT_BLADE_SCALE, (float) Math.cos(heading), (float) Math.sin(heading)};
    }

    /** Blade tip in mesh voxels. */
    static float[] karambitTip() {
        float[] c = karambitCurve(KARAMBIT_BLADE_LENGTH);
        return new float[]{(c[0] + KARAMBIT_BLADE_X) * KARAMBIT_DETAIL, (c[1] + KARAMBIT_BLADE_Y) * KARAMBIT_DETAIL};
    }

    /** Fade colour id for t in 0..1 (0 = purple, 1 = gold). */
    private static int fade(float t) {
        int i = Math.round(Math.max(0.0f, Math.min(1.0f, t)) * (KnifeMaterial.FADE_STEPS - 1));
        return KnifeMaterial.FADE_0 + i;
    }

    static KnifeMesh karambit() {
        KnifeMesh.Voxels v = new KnifeMesh.Voxels();
        float ringX = KARAMBIT_RING_X, ringY = KARAMBIT_RING_Y;

        // handle: the traced stepped band of black scales, three deep, with a red pin near the blade
        int lastX = Integer.MIN_VALUE, lastY = 0;
        for (int row = 0; row < HANDLE.length; row++) {
            for (int col = 0; col < HANDLE[row].length(); col++) {
                if (HANDLE[row].charAt(col) != '#') continue;
                int x = HANDLE_X0 + col, y = row;
                v.set(x, y, 0, KnifeMaterial.BLACK_DARK, grain(x, y, 0));
                for (int z : new int[]{-1, 1}) v.set(x, y, z, KnifeMaterial.BLACK, 0.85f * grain(x, y, z) + 0.15f * grain(x * 3, y * 5, z));
                if (x > lastX || x == lastX && y > lastY) { lastX = x; lastY = y; }
            }
        }
        v.set(lastX - 3, lastY - 1, 2, KnifeMaterial.RED, 1.0f);
        v.set(lastX - 3, lastY - 1, -2, KnifeMaterial.BLACK_DARK, 1.0f);

        // finger ring: a one-voxel tube, three deep, running through the same fade as the blade
        for (int x = Math.round(ringX - 3.6f); x <= Math.round(ringX + 3.6f); x++) {
            for (int y = Math.round(ringY - 3.6f); y <= Math.round(ringY + 3.6f); y++) {
                float dx = ((float) x + 0.5f) - ringX;
                float dy = ((float) y + 0.5f) - ringY;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < 1.9f || d > 3.2f || v.filled(x, y, 0)) continue;
                // angle around the ring: purple on the handle side, through crimson/orange, to gold at the top
                float ang = (float) Math.atan2(dy, dx);
                float t = 0.5f - 0.5f * (float) Math.cos(ang - 0.9f);
                int ink = fade(t * 0.95f);
                for (int z = -1; z <= 1; z++) v.set(x, y, z, ink, (z == 0 ? 1.0f : 0.86f) * grain(x, y, z));
            }
        }

        // curved blade: every voxel takes its position along/across the nearest point of the centre line
        int samples = 240;
        float[][] curve = new float[samples + 1][];
        for (int i = 0; i <= samples; i++) {
            float[] c = karambitCurve(KARAMBIT_BLADE_LENGTH * i / samples);
            curve[i] = new float[]{c[0] + KARAMBIT_BLADE_X, c[1] + KARAMBIT_BLADE_Y, c[2], c[3]};
        }
        int reachX = Math.round(40.0f * KARAMBIT_BLADE_SCALE), reachY = Math.round(14.0f * KARAMBIT_BLADE_WIDTH);
        float maxBladeX = KARAMBIT_BLADE_X;
        for (int x = KARAMBIT_BLADE_X - 4; x < KARAMBIT_BLADE_X + reachX; x++) {
            for (int y = KARAMBIT_BLADE_Y - reachX; y < KARAMBIT_BLADE_Y + reachY; y++) {
                float px = (float) x + 0.5f;
                float py = (float) y + 0.5f;
                int best = 0;
                float bestDistance = Float.MAX_VALUE;
                for (int i = 0; i <= samples; i++) {
                    float dx = px - curve[i][0], dy = py - curve[i][1];
                    float d = dx * dx + dy * dy;
                    if (d < bestDistance) { bestDistance = d; best = i; }
                }
                float[] c = curve[best];
                float dx = px - c[0], dy = py - c[1];
                float along = dx * c[2] + dy * c[3];
                float across = -dx * c[3] + dy * c[2];
                if (best == 0 && along < -KARAMBIT_ROOT_OVERLAP || best == samples && along > 0.3f) continue;
                float left = 1.0f - (float) best / samples;
                float spine = 1.3f * KARAMBIT_BLADE_WIDTH * (float) Math.pow(left, 0.7) + 0.2f;
                float edge = 1.8f * KARAMBIT_BLADE_WIDTH * (float) Math.pow(left, 1.0) + 0.2f;
                if (across > spine || across < -edge || v.filled(x, y, 0)) continue;
                float along01 = (float) best / samples;
                int ink = fade(along01 * 1.05f);
                float tone = across < -edge + 0.9f ? 1.12f : (across > spine - 0.9f ? 0.78f : 1.0f);   // bright edge, dark spine
                v.set(x, y, 0, ink, tone * grain(x, y, 0));
                // Cross-section: the blade used to be a single voxel thick next to a three-deep handle, so it read as a paper
                // sheet and vanished edge-on during the roll. The spine side and the root are now as deep as the handle and
                // only the cutting edge is ground down to one voxel (a visible bevel).
                float q = (across + edge) / (spine + edge);          // 0 at the cutting edge, 1 at the spine
                if (q > 0.45f || left > 0.88f) {
                    for (int z : new int[]{-1, 1}) v.set(x, y, z, ink, 0.86f * tone * grain(x, y, z));
                }
                if (x > maxBladeX) maxBladeX = x;
            }
        }
        // the highlight sweeps from the blade root to the tip (it used to run on to x = 24.5, a few voxels past the blade)
        return v.mesh(KARAMBIT_BLADE_X, maxBladeX + 1.5f);
    }

    // ---------------------------------------------------------------- butterfly

    /** The blade half: a tang (x < 0 metal) hinged at x = -1.5, then a clip-point blade out to x = 21. */
    static KnifeMesh butterflyBlade() {
        KnifeMesh.Voxels v = new KnifeMesh.Voxels();
        for (int x = -3; x <= 21; x++) {
            int top = x <= 12 ? 2 : Math.round(2.0f - (float) (x - 12) * 3.0f / 9.0f);
            int bottom = x <= 14 ? -2 : Math.round(-2.0f + (float) Math.pow((float) (x - 14) / 7.0f, 2.0) * 1.0f);
            for (int y = bottom; y <= top; y++) {
                int ink = x < 0 ? KnifeMaterial.METAL
                        : (x <= 1 ? KnifeMaterial.BLADE_DARK
                        : (y == bottom ? KnifeMaterial.EDGE
                        : (y == top ? (x >= 13 ? KnifeMaterial.EDGE : KnifeMaterial.BLADE_DARK) : KnifeMaterial.BLADE)));
                v.set(x, y, 0, ink, grain(x, y, 0));
            }
        }
        return v.mesh(0.0f, 22.0f);
    }

    /** One handle half (side +1 / -1): inner + outer scale, three holes, two pins, a latch hook on the -1 side. */
    static KnifeMesh butterflyHandle(int side) {
        KnifeMesh.Voxels v = new KnifeMesh.Voxels();
        int inner = side > 0 ? 1 : -1;
        int outer = side > 0 ? 2 : -2;
        int cap = side > 0 ? 3 : -3;
        for (int x = -27; x <= 0; x++) {
            for (int y = -2; y <= 2; y++) {
                boolean corner = (x == 0 || x == -27) && Math.abs(y) == 2;
                boolean hole = Math.abs(y) <= 1 && (x <= -6 && x >= -8 || x <= -12 && x >= -14 || x <= -18 && x >= -20);
                if (corner || hole) continue;
                v.set(x, y, inner, KnifeMaterial.HANDLE_DARK, grain(x, y, inner));
                int ink = KnifeMaterial.HANDLE;
                if ((x == -10 || x == -16) && Math.abs(y) <= 1) ink = KnifeMaterial.ACCENT;
                if (y == 0 && (x == -2 || x == -25)) ink = KnifeMaterial.METAL;
                v.set(x, y, outer, ink, grain(x, y, outer));
            }
        }
        v.set(-2, 0, cap, KnifeMaterial.METAL, 1.0f);
        v.set(-25, 0, cap, KnifeMaterial.METAL, 1.0f);
        if (side < 0) {
            v.set(-28, 1, inner, KnifeMaterial.METAL, 1.0f);
            v.set(-28, 2, inner, KnifeMaterial.METAL, 1.0f);
            v.set(-29, 2, inner, KnifeMaterial.METAL, 1.0f);
        }
        return v.mesh(-29.0f, 0.0f);
    }
}
