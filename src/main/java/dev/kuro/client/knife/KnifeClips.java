package dev.kuro.client.knife;

import java.util.HashMap;
import java.util.Map;

/**
 * Hand-keyed inspect + draw clips for the one-piece knives. Row layout: {time, x, y, z, yaw, pitch, roll, flip, part A, shine}
 * (see {@link #r}). Angles are unwrapped, so 720 is two full turns. Every clip ends on the rest pose: angles a multiple of
 * 360, part A back at 0 (folding knives: open) and shine at 1 (= finished).
 *
 * Part A means: folding knives, the blade on its hinge (180 = closed, 0 = open); ring knives, the whole knife spinning
 * around the finger ring. Item frame: +x right, +y up, +z toward you; positive pitch lifts the point, positive yaw swings
 * it left, positive roll turns it counter-clockwise as seen from your eyes.
 */
final class KnifeClips {
    /** {@code loopFrom < loopTo}: holding the inspect key loops that window (both must be key times). */
    record Set(float[][] inspect, float inspectSeconds, float loopFrom, float loopTo, float[][] deploy, float deploySeconds) {}

    private static final Map<String, Set> SETS = new HashMap<>();

    private KnifeClips() {}

    static Set of(String name) { return SETS.get(name); }

    private static float[] r(float t, float x, float y, float z, float yaw, float pitch, float roll, float flip, float a, float shine) {
        return new float[]{t, x, y, z, yaw, pitch, roll, flip, 0.0f, a, 0.0f, shine};
    }

    private static final float[] REST = r(1.0f, 0, 0, 0, 0, 0, 0, 0, 0, 1);

    /** Rest pose with {@code turns} full flips so the last key matches the knife at rest. */
    private static float[] end(float flip, float a) { return r(1.0f, 0, 0, 0, 0, 0, 0, flip, a, 1); }

    private static void put(String name, float[][] inspect, float seconds, float[][] deploy, float deploySeconds) {
        SETS.put(name, new Set(inspect, seconds, 0.0f, 0.0f, deploy, deploySeconds));
    }

    private static void putLoop(String name, float[][] inspect, float seconds, float[][] deploy, float deploySeconds) {
        SETS.put(name, new Set(inspect, seconds, 0.14f, 0.4f, deploy, deploySeconds));
    }

    static {
        // ---------------------------------------------------------------- katana: iaido draw, long wipe, chiburi flick
        put("Katana", new float[][]{
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.06f, 0.12f, 0.03f, 0, -8, 8, 0, 0, 0),
                r(0.28f, -0.08f, 0.16f, 0.03f, -18, -14, 22, 180, 0, 0),
                r(0.50f, -0.08f, 0.16f, 0.02f, 18, -8, -14, 360, 0, 0.9f),
                r(0.64f, -0.06f, 0.12f, 0.03f, -8, -10, -30, 360, 0, 1),
                r(0.74f, -0.02f, 0.16f, 0.01f, 0, -28, -8, 360, 0, 1),     // raise
                r(0.80f, -0.05f, 0.02f, 0.06f, 0, 30, 6, 360, 0, 1),       // chiburi: snap the point down
                r(0.90f, -0.02f, 0.03f, 0.02f, 0, -4, 3, 360, 0, 1),
                end(360, 0)}, 4.4f,
            new float[][]{
                r(0.00f, 0.12f, -0.14f, 0.06f, 50, -16, -60, 0, 0, 0),    // blade comes out low and to the side
                r(0.25f, 0.08f, -0.06f, 0.03f, 38, -6, -40, 0, 0, 0),
                r(0.50f, -0.04f, 0.04f, 0.0f, -14, 10, 12, 0, 0, 0.5f),   // slash across, highlight wipes the blade
                r(0.72f, 0, 0, 0, 5, -3, -4, 0, 0, 0.95f),
                end(0, 0)}, 1.0f);

        // ---------------------------------------------------------------- folding knives: hinge A, 180 = closed
        put("Flip Knife", new float[][]{
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.08f, 0.10f, 0.04f, -8, 8, 10, 0, 0, 0),
                r(0.22f, -0.10f, 0.12f, 0.05f, 0, 10, 14, 0, 100, 0),      // thumb pushes it shut
                r(0.30f, -0.10f, 0.12f, 0.05f, 4, 10, 14, 0, 180, 0),
                r(0.38f, -0.10f, 0.12f, 0.05f, 4, 14, 14, 0, 180, 0),      // closed, show it
                r(0.46f, -0.10f, 0.13f, 0.05f, 0, 6, 8, 0, -16, 0.3f),     // flick open, overshoot
                r(0.54f, -0.10f, 0.13f, 0.05f, 0, 6, 8, 0, 6, 0.5f),
                r(0.72f, -0.10f, 0.13f, 0.05f, 8, 8, -8, 360, 0, 0.9f),    // flip it over while open
                r(0.88f, -0.04f, 0.05f, 0.02f, -4, 3, 5, 360, 0, 1),
                end(360, 0)}, 3.4f,
            new float[][]{
                r(0.00f, 0.03f, -0.15f, 0.03f, 0, -30, -20, 0, 180, 0),    // comes up closed
                r(0.35f, 0.01f, -0.06f, 0.01f, 0, -10, -8, 0, 180, 0),
                r(0.52f, 0, -0.02f, 0, 0, 0, 0, 0, 150, 0),
                r(0.66f, 0, 0, 0, 0, 6, 6, 0, -14, 0),                     // snaps open
                r(0.80f, 0, 0, 0, 0, -2, -2, 0, 5, 0),
                end(0, 0)}, 1.0f);

        put("Navaja", new float[][]{
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.08f, 0.10f, 0.04f, -6, 6, 12, 0, 0, 0),
                r(0.28f, -0.10f, 0.13f, 0.05f, 12, 10, 22, 180, 0, 0.3f),   // show the other side
                r(0.45f, -0.10f, 0.13f, 0.05f, 8, 8, 10, 360, 60, 0.5f),    // starts to close
                r(0.58f, -0.10f, 0.12f, 0.05f, -8, 16, -20, 360, 175, 0.5f),
                r(0.70f, -0.10f, 0.12f, 0.05f, -8, 16, -20, 360, -12, 0.8f), // snaps open
                r(0.84f, -0.04f, 0.05f, 0.02f, 0, 4, 4, 360, 4, 1),
                end(360, 0)}, 3.8f,
            new float[][]{
                r(0.00f, 0.02f, -0.12f, 0.02f, 0, -20, -30, 0, 180, 0),
                r(0.40f, 0, -0.03f, 0, 0, -6, -8, 0, 180, 0),              // hold it closed a moment
                r(0.62f, 0, 0, 0, 0, 4, 4, 0, 90, 0),                      // long, slow open
                r(0.80f, 0, 0, 0, 0, -3, -3, 0, -10, 0),
                r(0.92f, 0, 0, 0, 0, 0, 0, 0, 3, 0),
                end(0, 0)}, 1.3f);

        // ---------------------------------------------------------------- ring knives: spin around the finger ring
        putLoop("Talon", new float[][]{
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.10f, 0.12f, 0.05f, -8, 12, 8, 0, -25, 0),
                r(0.14f, -0.12f, 0.13f, 0.05f, -8, 12, 8, 0, 0, 0),
                r(0.205f, -0.12f, 0.14f, 0.05f, -8, 12, 8, 0, 180, 0),
                r(0.27f, -0.12f, 0.13f, 0.05f, -8, 12, 8, 0, 360, 0),
                r(0.335f, -0.12f, 0.14f, 0.05f, -8, 12, 8, 0, 540, 0),
                r(0.40f, -0.12f, 0.13f, 0.05f, -8, 12, 8, 0, 720, 0),
                r(0.50f, -0.12f, 0.12f, 0.05f, 10, 8, 8, 0, 740, 0),
                r(0.62f, -0.10f, 0.12f, 0.04f, 18, -6, -20, 360, 720, 0.6f),  // twist the claw over
                r(0.78f, -0.08f, 0.10f, 0.04f, -6, 6, -8, 360, 720, 1),
                r(0.90f, -0.03f, 0.04f, 0.02f, 0, 2, 3, 360, 720, 1),
                end(360, 720)}, 3.2f,
            new float[][]{
                r(0.00f, 0.02f, -0.14f, 0.02f, 0, -30, -20, 0, -540, 0),
                r(0.55f, 0, 0, 0, 0, 4, 4, 0, -60, 0),
                r(0.78f, 0, 0, 0, 0, -2, -2, 0, 12, 0),
                end(0, 0)}, 0.8f);

        putLoop("Paracord", new float[][]{
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.08f, 0.10f, 0.04f, -6, 8, 10, 0, 20, 0),
                r(0.14f, -0.10f, 0.11f, 0.04f, -6, 8, 10, 0, 0, 0),
                r(0.205f, -0.10f, 0.12f, 0.04f, -6, 8, 10, 0, -180, 0),     // spins backwards
                r(0.27f, -0.10f, 0.11f, 0.04f, -6, 8, 10, 0, -360, 0),
                r(0.335f, -0.10f, 0.12f, 0.04f, -6, 8, 10, 0, -540, 0),
                r(0.40f, -0.10f, 0.11f, 0.04f, -6, 8, 10, 0, -720, 0),
                r(0.52f, -0.10f, 0.10f, 0.05f, 6, 14, 6, 0, -720, 0.3f),
                r(0.66f, -0.12f, 0.14f, 0.08f, 0, 24, -24, 180, -720, 0.7f), // swing the point toward you
                r(0.82f, -0.06f, 0.07f, 0.03f, -4, 4, 6, 360, -720, 1),
                end(360, -720)}, 3.4f,
            new float[][]{
                r(0.00f, -0.02f, -0.16f, 0.04f, 10, -35, 10, 0, 360, 0),
                r(0.50f, 0, -0.02f, 0, 0, 3, -3, 0, 40, 0),
                r(0.75f, 0, 0, 0, 0, -2, 2, 0, -8, 0),
                end(0, 0)}, 0.85f);

        putLoop("Skeleton", new float[][]{
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.10f, 0.13f, 0.05f, -10, 10, 8, 0, -30, 0),
                r(0.14f, -0.12f, 0.14f, 0.05f, -10, 10, 8, 0, 0, 0),
                r(0.205f, -0.12f, 0.15f, 0.05f, -10, 10, 8, 0, 180, 0),
                r(0.27f, -0.12f, 0.14f, 0.05f, -10, 10, 8, 0, 360, 0),
                r(0.335f, -0.12f, 0.15f, 0.05f, -10, 10, 8, 0, 540, 0),
                r(0.40f, -0.12f, 0.14f, 0.05f, -10, 10, 8, 0, 720, 0),
                r(0.48f, -0.12f, 0.13f, 0.05f, -10, 10, 8, 0, 900, 0.2f),    // faster second burst
                r(0.56f, -0.11f, 0.12f, 0.05f, -4, 8, 4, 0, 1260, 0.4f),
                r(0.70f, -0.09f, 0.10f, 0.04f, 12, -10, -26, 0, 1440, 0.8f),
                r(0.86f, -0.04f, 0.05f, 0.02f, -4, 3, 5, 0, 1440, 1),
                end(0, 1440)}, 3.2f,
            new float[][]{
                r(0.00f, 0.03f, -0.14f, 0.03f, 0, -30, -20, 0, -720, 0),
                r(0.50f, 0, 0, 0, 0, 4, 4, 0, -50, 0),
                r(0.75f, 0, 0, 0, 0, -2, -2, 0, 10, 0),
                end(0, 0)}, 0.8f);

        // ---------------------------------------------------------------- the rest: one flourish each
        put("Bayonet", new float[][]{                                         // stab, pull back, spin, salute
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.05f, 0.08f, 0.06f, -6, 10, 6, 0, 0, 0),
                r(0.18f, -0.05f, 0.07f, -0.14f, -2, -6, 0, 0, 0, 0),         // thrust
                r(0.28f, -0.08f, 0.10f, 0.03f, 0, 8, 10, 0, 0, 0),
                r(0.50f, -0.10f, 0.13f, 0.05f, 6, 10, 12, 360, 0, 0.3f),
                r(0.64f, -0.10f, 0.13f, 0.05f, -6, 10, -8, 720, 0, 0.7f),
                r(0.80f, -0.06f, 0.08f, 0.03f, -12, 6, -30, 720, 0, 1),
                r(0.92f, -0.02f, 0.03f, 0.01f, -3, 2, -4, 720, 0, 1),
                end(720, 0)}, 2.6f,
            new float[][]{
                r(0.00f, 0.02f, -0.10f, 0.10f, 0, -25, 0, -180, 0, 0),
                r(0.50f, 0, 0, -0.05f, 0, 4, 0, -20, 0, 0),
                r(0.75f, 0, 0, 0.01f, 0, -2, 0, 4, 0, 0),
                end(0, 0)}, 0.7f);

        put("M9 Bayonet", new float[][]{                                      // wide turns, point up, point down
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.07f, 0.10f, 0.04f, 0, 8, 8, 0, 0, 0),
                r(0.26f, -0.10f, 0.12f, 0.04f, 40, 6, 10, 180, 0, 0),
                r(0.42f, -0.10f, 0.12f, 0.04f, -40, 6, -10, 360, 0, 0.6f),
                r(0.58f, -0.10f, 0.13f, 0.05f, 0, 26, 0, 360, 0, 0.9f),      // point up
                r(0.74f, -0.08f, 0.12f, 0.04f, 0, -24, 0, 360, 0, 1),        // point down
                r(0.88f, -0.03f, 0.04f, 0.02f, 0, 3, 0, 360, 0, 1),
                end(360, 0)}, 3.2f,
            new float[][]{
                r(0.00f, 0, 0.18f, 0.02f, 0, 25, 0, 0, 0, 0),                // slammed down from above
                r(0.45f, 0, -0.04f, 0, 0, -6, 0, 0, 0, 0),
                r(0.65f, 0, 0.01f, 0, 0, 2, 0, 0, 0, 0),
                end(0, 0)}, 0.8f);

        put("Gut Knife", new float[][]{                                       // tilt the spine toward you (the hook), rock
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.12f, -0.08f, 0.10f, 0.04f, -6, 8, 8, 0, 0, 0),
                r(0.30f, -0.10f, 0.12f, 0.06f, 0, 18, -60, 0, 0, 0),
                r(0.50f, -0.10f, 0.12f, 0.06f, 8, 24, -80, 0, 0, 0.4f),
                r(0.66f, -0.10f, 0.12f, 0.06f, -6, 10, -20, 180, 0, 0.7f),
                r(0.80f, -0.08f, 0.10f, 0.05f, 4, 6, 6, 360, 0, 1),
                r(0.92f, -0.02f, 0.03f, 0.01f, 0, 1, 1, 360, 0, 1),
                end(360, 0)}, 2.8f,
            new float[][]{
                r(0.00f, 0.04f, -0.14f, 0.04f, 0, -10, 40, 0, 0, 0),
                r(0.50f, 0, -0.02f, 0, 0, 4, -8, 0, 0, 0),
                r(0.78f, 0, 0, 0, 0, -1, 2, 0, 0, 0),
                end(0, 0)}, 0.7f);

        put("Huntsman", new float[][]{                                        // slow, heavy, hold it to the light
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.14f, -0.08f, 0.12f, 0.05f, 0, 14, 6, 0, 0, 0),
                r(0.36f, -0.10f, 0.15f, 0.06f, -14, 22, 18, 180, 0, 0.2f),
                r(0.58f, -0.10f, 0.15f, 0.06f, 14, 22, -6, 360, 0, 0.8f),
                r(0.74f, -0.09f, 0.13f, 0.05f, 0, 34, -22, 360, 0, 1),       // point to the ceiling
                r(0.88f, -0.03f, 0.05f, 0.02f, 0, 3, 3, 360, 0, 1),
                end(360, 0)}, 4.0f,
            new float[][]{
                r(0.00f, -0.12f, -0.04f, 0.02f, -40, -4, 14, 0, 0, 0),       // swings in from the side
                r(0.50f, 0.02f, 0, 0, 6, 2, -4, 0, 0, 0),
                r(0.75f, 0, 0, 0, -2, 0, 1, 0, 0, 0),
                end(0, 0)}, 0.9f);

        put("Falchion", new float[][]{                                        // two cuts and a figure of eight
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.06f, 0.12f, 0.04f, 30, 10, 20, 0, 0, 0),
                r(0.22f, -0.10f, 0.06f, -0.04f, -30, -14, -40, 0, 0, 0.2f),  // cut 1
                r(0.34f, -0.06f, 0.12f, 0.04f, 30, 10, 20, 0, 0, 0),
                r(0.46f, -0.10f, 0.06f, -0.04f, -30, -14, -40, 0, 0, 0.4f),  // cut 2
                r(0.64f, -0.10f, 0.13f, 0.05f, 0, 14, 0, 360, 0, 0.8f),
                r(0.82f, -0.04f, 0.05f, 0.02f, 6, 2, 4, 360, 0, 1),
                end(360, 0)}, 3.0f,
            new float[][]{
                r(0.00f, 0, 0.16f, 0.02f, 0, 55, 0, 0, 0, 0),                // overhead chop
                r(0.40f, 0, -0.05f, -0.04f, 0, -22, 0, 0, 0, 0),
                r(0.62f, 0, 0.01f, 0, 0, 4, 0, 0, 0, 0),
                end(0, 0)}, 0.75f);

        put("Bowie", new float[][]{                                           // toss and catch
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.06f, 0.06f, 0.04f, 0, 4, 4, 0, 0, 0),
                r(0.30f, -0.08f, 0.20f, 0.04f, 0, 10, 0, 360, 0, 0.3f),      // up
                r(0.46f, -0.08f, 0.22f, 0.04f, 0, 12, 0, 720, 0, 0.6f),
                r(0.60f, -0.08f, 0.02f, 0.04f, 0, -4, 0, 720, 0, 0.9f),      // caught
                r(0.72f, -0.08f, 0.07f, 0.04f, 0, 6, 4, 720, 0, 1),
                r(0.88f, -0.03f, 0.03f, 0.01f, 0, 1, 1, 720, 0, 1),
                end(720, 0)}, 2.8f,
            new float[][]{
                r(0.00f, 0.10f, -0.20f, 0.04f, 0, -20, 10, 0, 0, 0),         // drawn up from low right, like from a sheath
                r(0.50f, 0.01f, -0.03f, 0, 0, 3, -2, 0, 0, 0),
                r(0.75f, 0, 0, 0, 0, -1, 1, 0, 0, 0),
                end(0, 0)}, 0.85f);

        put("Stiletto", new float[][]{                                        // fast pencil-twirl on its axis
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.07f, 0.09f, 0.03f, -4, 6, 8, 0, 0, 0),
                r(0.28f, -0.09f, 0.11f, 0.04f, 0, 8, 10, 540, 0, 0),
                r(0.46f, -0.09f, 0.11f, 0.04f, 2, 4, 6, 1080, 0, 0.3f),
                r(0.62f, -0.09f, 0.11f, 0.05f, 0, 20, 0, 1440, 0, 0.7f),
                r(0.80f, -0.05f, 0.06f, 0.02f, 0, 3, 2, 1440, 0, 1),
                end(1440, 0)}, 2.4f,
            new float[][]{
                r(0.00f, 0.00f, -0.04f, 0.14f, 0, -4, 0, 0, 0, 0),           // darts in from the front
                r(0.35f, 0, 0, -0.06f, 0, 2, 0, 0, 0, 0),
                r(0.60f, 0, 0, 0.01f, 0, 0, 0, 0, 0, 0),
                end(0, 0)}, 0.6f);

        put("Ursus", new float[][]{                                           // hunter's check: tip up, then tip down
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.12f, -0.08f, 0.10f, 0.04f, -6, 8, 8, 0, 0, 0),
                r(0.32f, -0.10f, 0.12f, 0.05f, 0, -34, 14, 90, 0, 0.2f),
                r(0.52f, -0.10f, 0.12f, 0.05f, 0, -34, 14, 180, 0, 0.5f),
                r(0.70f, -0.09f, 0.12f, 0.05f, 0, 28, -10, 270, 0, 0.9f),
                r(0.86f, -0.04f, 0.05f, 0.02f, 0, 3, 3, 360, 0, 1),
                end(360, 0)}, 3.0f,
            new float[][]{
                r(0.00f, 0, -0.10f, 0.06f, 0, -40, 0, 0, 0, 0),
                r(0.50f, 0, 0, 0, 0, 6, 0, 0, 0, 0),
                r(0.75f, 0, 0, 0, 0, -2, 0, 0, 0, 0),
                end(0, 0)}, 0.7f);

        put("Classic", new float[][]{                                         // the classic flip
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.14f, -0.08f, 0.10f, 0.04f, -6, 8, 6, 0, 0, 0),
                r(0.34f, -0.10f, 0.13f, 0.05f, 0, 12, 12, 360, 0, 0.3f),
                r(0.54f, -0.10f, 0.13f, 0.05f, 6, 6, -6, 720, 0, 0.7f),
                r(0.72f, -0.08f, 0.10f, 0.04f, -10, 14, -30, 720, 0, 1),
                r(0.88f, -0.03f, 0.04f, 0.02f, 0, 2, 2, 720, 0, 1),
                end(720, 0)}, 2.8f,
            new float[][]{
                r(0.00f, 0.02f, -0.12f, 0.02f, 0, -45, 0, 0, 0, 0),          // a salute
                r(0.50f, 0, -0.02f, 0, 0, 8, 0, 0, 0, 0),
                r(0.78f, 0, 0, 0, 0, -2, 0, 0, 0, 0),
                end(0, 0)}, 0.8f);

        put("Survival", new float[][]{                                        // show the saw back, tap the pommel
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.12f, -0.07f, 0.10f, 0.04f, -6, 8, 8, 0, 0, 0),
                r(0.32f, -0.10f, 0.13f, 0.06f, 0, 14, 70, 0, 0, 0.3f),       // spine toward you
                r(0.52f, -0.10f, 0.13f, 0.06f, 6, 16, 80, 0, 0, 0.6f),
                r(0.66f, -0.08f, 0.10f, 0.04f, 0, -26, 10, 0, 0, 0.8f),      // reverse: pommel up
                r(0.74f, -0.07f, 0.06f, 0.04f, 0, -10, 6, 0, 0, 0.9f),       // tap
                r(0.88f, -0.03f, 0.04f, 0.02f, 0, 2, 2, 0, 0, 1),
                end(0, 0)}, 3.0f,
            new float[][]{
                r(0.00f, 0, 0.16f, 0.04f, 0, 30, 0, 0, 0, 0),                // heavy drop
                r(0.40f, 0, -0.06f, 0, 0, -8, 0, 0, 0, 0),
                r(0.62f, 0, 0.015f, 0, 0, 2, 0, 0, 0, 0),
                end(0, 0)}, 0.85f);

        put("Nomad", new float[][]{                                           // wrist flicks, curved blade sweeps
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.10f, -0.07f, 0.09f, 0.04f, -8, 8, 8, 0, 0, 0),
                r(0.24f, -0.10f, 0.11f, 0.05f, 24, 6, 20, 180, 0, 0.2f),
                r(0.38f, -0.10f, 0.11f, 0.05f, -24, 6, -20, 360, 0, 0.4f),
                r(0.52f, -0.10f, 0.11f, 0.05f, 24, 6, 20, 540, 0, 0.6f),
                r(0.66f, -0.10f, 0.12f, 0.05f, -10, 10, -10, 720, 0, 0.9f),
                r(0.84f, -0.04f, 0.05f, 0.02f, 0, 2, 2, 720, 0, 1),
                end(720, 0)}, 3.0f,
            new float[][]{
                r(0.00f, -0.06f, -0.10f, 0.03f, -30, -10, -40, 0, 0, 0),
                r(0.50f, 0.01f, -0.01f, 0, 4, 2, 6, 0, 0, 0),
                r(0.78f, 0, 0, 0, -1, 0, -1, 0, 0, 0),
                end(0, 0)}, 0.75f);

        put("Kukri", new float[][]{                                           // heavy chops
                r(0.00f, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                r(0.14f, -0.07f, 0.14f, 0.04f, 0, 40, 6, 0, 0, 0),           // wind up
                r(0.28f, -0.10f, 0.04f, -0.04f, 0, -40, 0, 0, 0, 0.2f),      // chop
                r(0.44f, -0.07f, 0.14f, 0.04f, 0, 40, 6, 0, 0, 0.2f),
                r(0.58f, -0.10f, 0.04f, -0.04f, 0, -40, 0, 0, 0, 0.4f),      // chop
                r(0.76f, -0.10f, 0.12f, 0.05f, 0, 10, 0, 360, 0, 0.9f),
                r(0.90f, -0.03f, 0.04f, 0.02f, 0, 2, 2, 360, 0, 1),
                end(360, 0)}, 3.0f,
            new float[][]{
                r(0.00f, 0, 0.20f, 0.02f, 0, 50, 0, 0, 0, 0),
                r(0.40f, 0, -0.06f, -0.03f, 0, -20, 0, 0, 0, 0),
                r(0.62f, 0, 0.01f, 0, 0, 4, 0, 0, 0, 0),
                end(0, 0)}, 0.8f);
    }
}
