package dev.kuro.client.knife;

import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;

/**
 * One-piece knives built from a small {@link Spec}: a blade outline (spine + edge height along the blade, optionally bent),
 * a guard, a handle and a pommel. Model space matches the other knives: +X along the blade away from the handle, +Y towards
 * the spine, Z across the thickness, the guard sits at x = 0 and the handle runs back along negative x.
 */
final class FixedKnives {
    enum Grip { PLAIN, GROOVES, WRAP, SCALES, STAG, SKELETON, TSUKA }
    enum Guard { NONE, CROSS, BRASS, FINGER, DISC }
    enum Pommel { NONE, CAP, BRASS_CAP, KNOB, RING }

    private FixedKnives() {}

    static final class Spec {
        final String name;
        float voxel = 0.026f;
        int blade = 22;                                   // blade length in voxels (x = 0 .. blade)
        DoubleUnaryOperator top = x -> 2.5;               // spine height at x
        DoubleUnaryOperator bottom = x -> -2.5;           // edge height at x
        DoubleUnaryOperator bend = x -> 0.0;              // sideways sweep of the whole blade (curved knives)
        float edgeBand = 0.7f, spineBand = 0.0f;          // bright edge strip / dark spine strip, in voxels
        boolean doubleEdge, thickSpine, fuller, hamon;
        int fullerFrom = 3, fullerTo = 16;
        int serrFrom, serrTo;                             // spine serrations between these x
        int handle = 12, handleZ = 1;                     // handle length, half thickness (cells either side of z = 0)
        double handleHalf = 2.0, handleCy = 0.0;
        DoubleUnaryOperator flare = d -> 0.0;             // extra half height, by distance from the tail end
        DoubleUnaryOperator handleBend = x -> 0.0;
        Grip grip = Grip.PLAIN;
        Guard guard = Guard.NONE;
        int guardLen = 2, guardReach = 2;
        Pommel pommel = Pommel.CAP;
        float gripBack = -2.0f;                           // voxels of handle behind the fist (negative: the fist sits behind the tail, so the whole handle shows)
        float shineTail;                                  // > 0: long wipe highlight (katana)
        Consumer<KnifeMesh.Voxels> post = v -> {};        // last-minute holes and decoration (handle side when folding)
        Consumer<KnifeMesh.Voxels> postBlade = null;      // decoration that belongs to the blade part
        boolean folding;                                  // handle and blade are separate parts hinged at x = HINGE_X
        boolean ring;                                     // ring pommel: the knife spins around it like the karambit

        Spec(String name) { this.name = name; }

        float gripX() { return -(handle - gripBack); }
        float hingeY() { return (float) handleCy; }
        float ringX() { return -handle - 2.5f; }
        float ringY() { return (float) (handleCy + handleBend.applyAsDouble(-handle + 0.5)); }
        float gripY() { return (float) (handleCy + handleBend.applyAsDouble(-handle / 2.0)); }
        float bladeEndY() { return (float) ((top.applyAsDouble(blade - 0.5) + bottom.applyAsDouble(blade - 0.5)) / 2.0 + bend.applyAsDouble(blade - 0.5)); }
        float bladeMidY(double x) { return (float) ((top.applyAsDouble(x) + bottom.applyAsDouble(x)) / 2.0 + bend.applyAsDouble(x)); }
    }

    /** Height that stays at {@code base} until x = start, then eases to {@code tip} at x = end. */
    static DoubleUnaryOperator taper(double base, double tip, double start, double end, double power) {
        return x -> {
            if (x <= start) return base;
            double t = Math.min(1.0, (x - start) / (end - start));
            return base + (tip - base) * Math.pow(t, power);
        };
    }

    /** Hinge of the folding knives (model x), just inside the handle's front end. */
    static final float HINGE_X = -1.0f;

    static final int WHOLE = 0, HANDLE_ONLY = 1, BLADE_ONLY = 2;

    static KnifeMesh build(Spec s) { return build(s, WHOLE); }

    /** {@code part}: the whole knife, or just the handle / just the blade (folding knives are two meshes on a hinge). */
    static KnifeMesh build(Spec s, int part) {
        KnifeMesh.Voxels v = new KnifeMesh.Voxels();
        if (part != HANDLE_ONLY) fillBlade(v, s);
        if (part != BLADE_ONLY) {
            fillHandle(v, s);
            fillGuard(v, s);
            fillPommel(v, s);
            s.post.accept(v);
        }
        if (s.folding && part != BLADE_ONLY) {                                  // hinge pin caps on both faces
            int hz = s.handleZ + 1;
            int py = (int) Math.floor(s.handleCy);
            v.set((int) HINGE_X, py, hz, KnifeMaterial.METAL, 1.0f);
            v.set((int) HINGE_X, py, -hz, KnifeMaterial.METAL, 1.0f);
        }
        if (s.folding && part != HANDLE_ONLY) {                                 // tang between the hinge and the blade
            int py = (int) Math.floor(s.handleCy);
            for (int x = (int) HINGE_X - 1; x < 0; x++)
                for (int y = py - 1; y <= py; y++) v.set(x, y, 0, KnifeMaterial.BLADE_DARK, KnifeModels.grain(x, y, 0));
        }
        if (part != HANDLE_ONLY && s.postBlade != null) s.postBlade.accept(v);
        return v.mesh(0.0f, s.blade);
    }



    // ------------------------------------------------------------------------------------------------ blade

    private static void fillBlade(KnifeMesh.Voxels v, Spec s) {
        for (int x = 0; x < s.blade; x++) {
            double xc = x + 0.5;
            double b = s.bend.applyAsDouble(xc);
            double top = s.top.applyAsDouble(xc) + b, bot = s.bottom.applyAsDouble(xc) + b;
            if (top <= bot) continue;
            boolean placed = false;
            for (int y = (int) Math.floor(bot) - 1; y <= (int) Math.ceil(top) + 1; y++) {
                double yc = y + 0.5;
                if (yc < bot || yc > top) continue;
                placed = true;
                bladeCell(v, s, x, y, xc, yc, top, bot);
            }
            if (!placed) {                                  // very thin tip: keep the outline unbroken
                int y = (int) Math.floor((top + bot) / 2.0);
                v.set(x, y, 0, KnifeMaterial.EDGE, KnifeModels.grain(x, y, 0));
            }
        }
        if (s.serrTo > s.serrFrom) {
            for (int x = s.serrFrom; x < s.serrTo; x += 3) {
                int y = topCell(v, x);
                if (y == Integer.MIN_VALUE) continue;
                for (int z = -1; z <= 1; z++) { v.clear(x, y, z); v.clear(x, y - 1, z); }
            }
        }
    }

    private static int topCell(KnifeMesh.Voxels v, int x) {
        for (int y = 31; y >= -32; y--) if (v.filled(x, y, 0)) return y;
        return Integer.MIN_VALUE;
    }

    private static void bladeCell(KnifeMesh.Voxels v, Spec s, int x, int y, double xc, double yc, double top, double bot) {
        double band = s.hamon ? 0.35 * (top - bot) + 0.45 * Math.sin(xc * 0.6) : s.edgeBand;
        int ink;
        if (yc - bot < band) ink = KnifeMaterial.EDGE;
        else if (s.doubleEdge && top - yc < s.edgeBand) ink = KnifeMaterial.EDGE;
        else if (s.spineBand > 0 && top - yc < s.spineBand) ink = KnifeMaterial.BLADE_DARK;
        else if (s.fuller && xc > s.fullerFrom && xc < s.fullerTo && Math.abs(yc - (top + bot) / 2.0 - 0.3) < 0.5) ink = KnifeMaterial.BLADE_DARK;
        else ink = KnifeMaterial.BLADE;
        v.set(x, y, 0, ink, KnifeModels.grain(x, y, 0));
        if (s.thickSpine && top - yc < 1.1) {
            v.set(x, y, -1, KnifeMaterial.BLADE_DARK, KnifeModels.grain(x, y, -1));
            v.set(x, y, 1, KnifeMaterial.BLADE_DARK, KnifeModels.grain(x, y, 1));
        }
    }

    // ------------------------------------------------------------------------------------------------ handle

    private static double handleTop(Spec s, double xc) {
        return s.handleCy + s.handleHalf + s.flare.applyAsDouble(xc + s.handle) + s.handleBend.applyAsDouble(xc);
    }

    private static double handleBottom(Spec s, double xc) {
        return s.handleCy - s.handleHalf - s.flare.applyAsDouble(xc + s.handle) + s.handleBend.applyAsDouble(xc);
    }

    private static void fillHandle(KnifeMesh.Voxels v, Spec s) {
        int hz = s.handleZ;
        for (int x = -s.handle; x < 0; x++) {
            double xc = x + 0.5;
            double top = handleTop(s, xc), bot = handleBottom(s, xc);
            for (int y = (int) Math.floor(bot) - 1; y <= (int) Math.ceil(top) + 1; y++) {
                double yc = y + 0.5;
                if (yc < bot || yc > top) continue;
                if (s.grip == Grip.SKELETON && skeletonHole(s, x, yc)) continue;
                boolean rim = top - yc < 0.9 || yc - bot < 0.9;
                for (int z = -hz; z <= hz; z++) {
                    if (hz >= 2 && Math.abs(z) == hz && rim) continue;      // round the corners of chunky handles
                    v.set(x, y, z, handleInk(s, x, y, z, hz, rim), KnifeModels.grain(x, y, z));
                }
            }
        }
        if (s.grip == Grip.SCALES) {                                         // rivets
            int[] at = {-(s.guardLen + 2), -s.handle / 2, -s.handle + 2};
            int y = (int) Math.floor(s.handleCy);
            for (int x : at) {
                v.set(x, y, hz + 1, KnifeMaterial.METAL, 1.0f);
                v.set(x, y, -hz - 1, KnifeMaterial.METAL, 1.0f);
            }
        }
    }

    private static boolean skeletonHole(Spec s, int x, double yc) {
        if (x > -4 || x < -s.handle / 2 - 1) return false;
        int phase = Math.floorMod(x + s.handle, 5);
        return phase >= 1 && phase <= 3 && Math.abs(yc - s.handleCy) < s.handleHalf - 0.9;
    }

    private static int handleInk(Spec s, int x, int y, int z, int hz, boolean rim) {
        if (Math.abs(z) < hz) return KnifeMaterial.HANDLE_DARK;
        switch (s.grip) {
            case GROOVES:
                return rim || Math.floorMod(x, 3) == 0 ? KnifeMaterial.HANDLE_DARK : KnifeMaterial.HANDLE;
            case WRAP:
                return ((x + y) & 3) < 2 ? KnifeMaterial.WRAP : KnifeMaterial.HANDLE;
            case STAG: {
                int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
                h ^= h >>> 13;
                if ((h & 7) == 0) return KnifeMaterial.HANDLE_DARK;
                return (h >>> 4 & 15) == 0 ? KnifeMaterial.ACCENT : KnifeMaterial.HANDLE;
            }
            case SKELETON:
                return x <= -s.handle / 2 && ((x + y) & 3) < 2 ? KnifeMaterial.WRAP : KnifeMaterial.METAL;
            case TSUKA: {
                int a = rim ? z : y;
                boolean ito = Math.floorMod(x + a, 6) < 2 || Math.floorMod(x - a, 6) < 2;
                return ito ? KnifeMaterial.WRAP : KnifeMaterial.ACCENT;
            }
            case SCALES:
            case PLAIN:
            default:
                return rim ? KnifeMaterial.HANDLE_DARK : KnifeMaterial.HANDLE;
        }
    }

    // ------------------------------------------------------------------------------------------------ guard / pommel

    private static void fillGuard(KnifeMesh.Voxels v, Spec s) {
        if (s.guard == Guard.NONE) return;
        int gz = s.handleZ + 1;
        int ink = s.guard == Guard.BRASS ? KnifeMaterial.GOLD : KnifeMaterial.METAL;
        double top = handleTop(s, -0.5), bot = handleBottom(s, -0.5);
        double cy = (top + bot) / 2.0;
        for (int x = -s.guardLen; x < 0; x++) {
            if (s.guard == Guard.DISC) {
                double r = (top - bot) / 2.0 + s.guardReach;
                for (int y = (int) Math.floor(cy - r) - 1; y <= (int) Math.ceil(cy + r); y++) {
                    for (int z = -8; z < 8; z++) {
                        double dy = y + 0.5 - cy, dz = z;
                        double d = Math.sqrt(dy * dy + dz * dz);
                        if (d > r) continue;
                        boolean rim = d > r - 1.0;
                        v.set(x, y, z, rim ? KnifeMaterial.GOLD : ink, KnifeModels.grain(x, y, z));
                    }
                }
                continue;
            }
            int y0 = (int) Math.floor(bot - (s.guard == Guard.FINGER ? s.guardReach * 2 : s.guardReach));
            int y1 = (int) Math.ceil(top + (s.guard == Guard.FINGER ? 0.5 : s.guardReach));
            for (int y = y0; y < y1; y++) for (int z = -gz; z <= gz; z++) v.set(x, y, z, ink, KnifeModels.grain(x, y, z));
        }
    }

    private static void fillPommel(KnifeMesh.Voxels v, Spec s) {
        if (s.pommel == Pommel.NONE) return;
        int hz = s.handleZ;
        double xc = -s.handle + 0.5;
        double top = handleTop(s, xc), bot = handleBottom(s, xc);
        int y0 = (int) Math.floor(bot), y1 = (int) Math.ceil(top);
        int x = -s.handle - 1;
        switch (s.pommel) {
            case CAP:
            case BRASS_CAP: {
                int ink = s.pommel == Pommel.BRASS_CAP ? KnifeMaterial.GOLD : KnifeMaterial.METAL;
                for (int y = y0; y < y1; y++) for (int z = -hz; z <= hz; z++) v.set(x, y, z, ink, KnifeModels.grain(x, y, z));
                break;
            }
            case KNOB:
                for (int xx = x - 1; xx <= x; xx++)
                    for (int y = y0 - 1; y < y1 + 1; y++)
                        for (int z = -hz - 1; z <= hz + 1; z++) {
                            boolean corner = (y == y0 - 1 || y == y1) && Math.abs(z) == hz + 1;
                            if (!corner) v.set(xx, y, z, KnifeMaterial.METAL, KnifeModels.grain(xx, y, z));
                        }
                break;
            case RING: {
                double cx = x - 1.5, cy = (top + bot) / 2.0;
                for (int xx = (int) Math.floor(cx - 3.2); xx <= Math.ceil(cx + 3.2); xx++)
                    for (int y = (int) Math.floor(cy - 3.2); y <= Math.ceil(cy + 3.2); y++) {
                        double d = Math.hypot(xx + 0.5 - cx, y + 0.5 - cy);
                        if (d < 1.5 || d > 3.0 || v.filled(xx, y, 0)) continue;
                        for (int z = -1; z <= 1; z++) v.set(xx, y, z, KnifeMaterial.METAL, (d > 2.6 || d < 1.9 ? 0.86f : 1.0f) * KnifeModels.grain(xx, y, z));
                    }
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------------------------------------ the knives

    static Spec[] all() {
        return new Spec[]{katana(), bayonet(), m9(), flip(), gut(), huntsman(), falchion(), bowie(), navaja(),
                stiletto(), talon(), ursus(), classic(), paracord(), survival(), nomad(), skeleton(), kukri()};
    }

    /** Long, gently curved blade with a hamon along the edge, round tsuba, diamond-wrapped tsuka. */
    static Spec katana() {
        Spec s = new Spec("Katana");
        s.voxel = 0.0145f;
        s.blade = 54;
        s.top = taper(2.0, 0.2, 47, 54, 1.0);
        s.bottom = taper(-2.0, 0.2, 49, 54, 1.0);
        s.bend = x -> 0.0015 * x * x;
        s.hamon = true;
        s.spineBand = 0.55f;
        s.fuller = true; s.fullerFrom = 4; s.fullerTo = 46;
        s.handle = 21; s.handleZ = 2; s.handleHalf = 1.85;
        s.grip = Grip.TSUKA;
        s.guard = Guard.DISC; s.guardLen = 2; s.guardReach = 2;
        s.pommel = Pommel.BRASS_CAP;
        s.gripBack = 0.0f;
        s.shineTail = 9.0f;
        s.post = v -> {
            for (int x = 0; x <= 1; x++)                                       // habaki collar
                for (int y = -2; y <= 1; y++)
                    for (int z = -1; z <= 1; z++) v.set(x, y, z, KnifeMaterial.GOLD, KnifeModels.grain(x, y, z));
            for (int x : new int[]{-13, -8})                                   // menuki
                for (int z : new int[]{-3, 2}) v.set(x, 0, z, KnifeMaterial.GOLD, 1.0f);
        };
        return s;
    }

    /** M1-style bayonet: spear point, fuller, cross guard, knob pommel. */
    static Spec bayonet() {
        Spec s = new Spec("Bayonet");
        s.blade = 24;
        s.top = taper(2.4, 0.3, 15, 24, 1.3);
        s.bottom = taper(-2.4, 0.3, 18, 24, 1.7);
        s.fuller = true; s.fullerFrom = 3; s.fullerTo = 18;
        s.handle = 11; s.handleHalf = 1.9;
        s.grip = Grip.GROOVES;
        s.guard = Guard.CROSS; s.guardReach = 2;
        s.pommel = Pommel.KNOB;
        return s;
    }

    /** Wide clip-point bayonet with a wire-cutter hole and a serrated spine. */
    static Spec m9() {
        Spec s = new Spec("M9 Bayonet");
        s.blade = 27;
        s.top = taper(3.0, 0.4, 17, 27, 1.15);
        s.bottom = taper(-3.0, 0.4, 20, 27, 1.6);
        s.fuller = true; s.fullerFrom = 8; s.fullerTo = 22;
        s.serrFrom = 3; s.serrTo = 14;
        s.handle = 12; s.handleHalf = 2.2;
        s.grip = Grip.GROOVES;
        s.guard = Guard.CROSS; s.guardLen = 2; s.guardReach = 2;
        s.pommel = Pommel.KNOB;
        s.post = v -> {
            for (int x = 4; x <= 6; x++) for (int y = -1; y <= 0; y++) v.clear(x, y, 0);
        };
        return s;
    }

    /** Folding drop-point with a thumb stud and metal bolsters. */
    static Spec flip() {
        Spec s = new Spec("Flip Knife");
        s.voxel = 0.022f;
        s.blade = 19;
        s.top = taper(2.8, 0.4, 10, 19, 1.6);
        s.bottom = taper(-2.8, 0.4, 5, 19, 2.0);
        s.handle = 21; s.handleHalf = 2.9;
        s.grip = Grip.SCALES;
        s.guard = Guard.CROSS; s.guardLen = 1; s.guardReach = 0;
        s.pommel = Pommel.CAP;
        s.folding = true;
        s.postBlade = v -> {
            v.set(1, 2, 1, KnifeMaterial.METAL, 1.0f);                         // thumb stud
            v.set(1, 2, -1, KnifeMaterial.METAL, 1.0f);
        };
        s.post = v -> { for (int y = -2; y <= 2; y++) v.set(-19, y, 0, KnifeMaterial.METAL, 1.0f); };   // bolster
        return s;
    }

    /** Short, broad skinning blade with a gut-hook notch. */
    static Spec gut() {
        Spec s = new Spec("Gut Knife");
        s.blade = 19;
        s.top = taper(3.2, 0.5, 8, 19, 1.4);
        s.bottom = taper(-3.2, 0.5, 3, 19, 2.3);
        s.handle = 12; s.handleHalf = 2.4;
        s.grip = Grip.GROOVES;
        s.guard = Guard.CROSS; s.guardLen = 1; s.guardReach = 1;
        s.pommel = Pommel.CAP;
        s.post = v -> {
            for (int x = 8; x <= 10; x++) { int y = topCell(v, x); v.clear(x, y, 0); v.clear(x, y - 1, 0); if (x == 9) v.clear(x, y - 2, 0); }
        };
        return s;
    }

    /** Wide blade with a deep belly, finger guard and stag-style handle. */
    static Spec huntsman() {
        Spec s = new Spec("Huntsman");
        s.blade = 23;
        s.top = taper(3.4, 0.5, 11, 23, 1.2);
        s.bottom = taper(-3.6, 0.5, 2, 23, 2.4);
        s.handle = 12; s.handleHalf = 2.3;
        s.grip = Grip.STAG;
        s.guard = Guard.FINGER; s.guardLen = 2; s.guardReach = 2;
        s.pommel = Pommel.KNOB;
        return s;
    }

    /** Heavy curved cutting blade, wide towards the point. */
    static Spec falchion() {
        Spec s = new Spec("Falchion");
        s.blade = 29;
        s.top = taper(2.3, 0.5, 7, 29, 1.0);
        s.bottom = x -> x < 29 ? -2.3 - 2.4 * Math.sin(Math.PI * Math.min(1.0, x / 29.0) * 0.9) + 2.8 * Math.pow(Math.max(0.0, (x - 21) / 8.0), 2.0) : 0.5;
        s.bend = x -> 0.0045 * x * x;
        s.thickSpine = true;
        s.handle = 12; s.handleHalf = 1.9;
        s.grip = Grip.WRAP;
        s.guard = Guard.FINGER; s.guardLen = 2; s.guardReach = 2;
        s.pommel = Pommel.KNOB;
        return s;
    }

    /** Big clip point, brass crossguard and a leather-style grip. */
    static Spec bowie() {
        Spec s = new Spec("Bowie");
        s.blade = 27;
        s.top = taper(3.4, 0.6, 17, 27, 0.55);
        s.bottom = taper(-3.4, 0.6, 6, 27, 2.4);
        s.thickSpine = true;
        s.handle = 13; s.handleHalf = 2.2;
        s.grip = Grip.GROOVES;
        s.guard = Guard.BRASS; s.guardLen = 2; s.guardReach = 3;
        s.pommel = Pommel.BRASS_CAP;
        return s;
    }

    /** Slim folding blade with a long taper and a curved bone handle. */
    static Spec navaja() {
        Spec s = new Spec("Navaja");
        s.voxel = 0.021f;
        s.blade = 23;
        s.top = taper(2.2, 0.2, 2, 23, 1.1);
        s.bottom = taper(-2.2, 0.2, 10, 23, 1.6);
        s.handle = 25; s.handleHalf = 2.5;
        s.handleBend = x -> -0.012 * (x + 12.5) * (x + 12.5) + 1.9;
        s.grip = Grip.SCALES;
        s.guard = Guard.CROSS; s.guardLen = 1; s.guardReach = 0;
        s.pommel = Pommel.CAP;
        s.folding = true;
        s.gripBack = -1.0f;
        return s;
    }

    /** Thin double-edged dagger, tight guard, wrapped grip. */
    static Spec stiletto() {
        Spec s = new Spec("Stiletto");
        s.voxel = 0.027f;
        s.blade = 27;
        s.top = taper(1.6, 0.2, 0, 27, 0.9);
        DoubleUnaryOperator half = taper(1.6, 0.2, 0, 27, 0.9);
        s.bottom = x -> -half.applyAsDouble(x);
        s.doubleEdge = true;
        s.fuller = true; s.fullerFrom = 2; s.fullerTo = 20;
        s.handle = 10; s.handleHalf = 1.5;
        s.flare = d -> d < 3 ? (3 - d) * 0.25 : 0.0;
        s.grip = Grip.WRAP;
        s.guard = Guard.CROSS; s.guardLen = 1; s.guardReach = 2;
        s.pommel = Pommel.KNOB;
        s.gripBack = -3.0f;
        return s;
    }

    /** Hawkbill: a short, strongly hooked blade and a ring pommel. */
    static Spec talon() {
        Spec s = new Spec("Talon");
        s.voxel = 0.023f;
        s.blade = 19;
        s.top = taper(2.4, 0.3, 4, 19, 1.0);
        s.bottom = taper(-2.4, 0.3, 4, 19, 1.0);
        s.bend = x -> -0.02 * x * x;
        s.thickSpine = true;
        s.handle = 12; s.handleHalf = 1.9;
        s.grip = Grip.GROOVES;
        s.guard = Guard.FINGER; s.guardLen = 1; s.guardReach = 1;
        s.pommel = Pommel.RING;
        s.gripBack = -2.5f;
        s.ring = true;
        return s;
    }

    /** Hunting knife with a swept point, jimped spine and a finger choil. */
    static Spec ursus() {
        Spec s = new Spec("Ursus");
        s.blade = 22;
        s.top = taper(2.8, 0.5, 12, 22, 1.0);
        s.bottom = taper(-2.8, 0.5, 2, 22, 2.2);
        s.serrFrom = 1; s.serrTo = 9;
        s.handle = 12; s.handleHalf = 2.2;
        s.grip = Grip.GROOVES;
        s.guard = Guard.FINGER; s.guardLen = 1; s.guardReach = 1;
        s.pommel = Pommel.KNOB;
        s.post = v -> { for (int y = -4; y <= -3; y++) v.clear(-1, y, 0); };
        return s;
    }

    /** Traditional clip point with a brass guard and stag handle. */
    static Spec classic() {
        Spec s = new Spec("Classic");
        s.blade = 23;
        s.top = taper(2.6, 0.4, 14, 23, 1.5);
        s.bottom = taper(-2.6, 0.4, 9, 23, 1.6);
        s.fuller = true; s.fullerFrom = 3; s.fullerTo = 15;
        s.handle = 12; s.handleHalf = 2.1;
        s.grip = Grip.STAG;
        s.guard = Guard.BRASS; s.guardLen = 2; s.guardReach = 2;
        s.pommel = Pommel.BRASS_CAP;
        return s;
    }

    /** Drop point with a paracord-wrapped handle and a lanyard ring. */
    static Spec paracord() {
        Spec s = new Spec("Paracord");
        s.voxel = 0.023f;
        s.blade = 20;
        s.top = taper(2.5, 0.4, 10, 20, 1.5);
        s.bottom = taper(-2.5, 0.4, 4, 20, 2.0);
        s.handle = 13; s.handleHalf = 2.2; s.handleZ = 2;
        s.grip = Grip.WRAP;
        s.guard = Guard.NONE;
        s.pommel = Pommel.RING;
        s.gripBack = -2.5f;
        s.ring = true;
        return s;
    }

    /** Big serrated survival blade with a hollow-handle cap. */
    static Spec survival() {
        Spec s = new Spec("Survival");
        s.blade = 26;
        s.top = taper(3.2, 0.6, 17, 26, 0.9);
        s.bottom = taper(-3.2, 0.6, 16, 26, 1.5);
        s.thickSpine = true;
        s.serrFrom = 3; s.serrTo = 16;
        s.handle = 13; s.handleHalf = 2.4; s.handleZ = 2;
        s.grip = Grip.GROOVES;
        s.guard = Guard.CROSS; s.guardLen = 2; s.guardReach = 2;
        s.pommel = Pommel.KNOB;
        return s;
    }

    /** Lightly recurved blade with a finger guard and a flared wrapped handle. */
    static Spec nomad() {
        Spec s = new Spec("Nomad");
        s.blade = 22;
        s.top = taper(2.6, 0.3, 6, 22, 1.0);
        s.bottom = taper(-2.6, 0.3, 6, 22, 1.2);
        s.bend = x -> -0.012 * x * x;
        s.thickSpine = true;
        s.handle = 12; s.handleHalf = 2.0;
        s.flare = d -> d < 3 ? (3 - d) * 0.3 : 0.0;
        s.grip = Grip.WRAP;
        s.guard = Guard.FINGER; s.guardLen = 2; s.guardReach = 2;
        s.pommel = Pommel.CAP;
        return s;
    }

    /** Full-tang knife: the handle is a metal frame with cut-outs, wrapped at the tail. */
    static Spec skeleton() {
        Spec s = new Spec("Skeleton");
        s.voxel = 0.022f;
        s.blade = 21;
        s.top = taper(2.6, 0.4, 11, 21, 1.5);
        s.bottom = taper(-2.6, 0.4, 5, 21, 2.0);
        s.handle = 16; s.handleHalf = 2.4;
        s.grip = Grip.SKELETON;
        s.guard = Guard.NONE;
        s.pommel = Pommel.RING;
        s.gripBack = -2.5f;
        s.ring = true;
        return s;
    }

    /** Heavy forward-weighted blade, inward curve, flared pommel. */
    static Spec kukri() {
        Spec s = new Spec("Kukri");
        s.blade = 27;
        s.top = taper(3.4, 0.5, 14, 27, 0.9);
        s.bottom = taper(-3.4, 0.5, 10, 27, 1.4);
        s.bend = x -> -0.011 * x * x;
        s.thickSpine = true;
        s.handle = 12; s.handleHalf = 2.0;
        s.flare = d -> d < 4 ? (4 - d) * 0.35 : 0.0;
        s.grip = Grip.SCALES;
        s.guard = Guard.CROSS; s.guardLen = 1; s.guardReach = 1;
        s.pommel = Pommel.KNOB;
        s.post = v -> { v.clear(1, -3, 0); v.clear(2, -3, 0); v.clear(1, -4, 0); };
        return s;
    }
}
