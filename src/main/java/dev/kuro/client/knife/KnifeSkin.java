package dev.kuro.client.knife;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One knife: its model parts, where the fist grips it, its rest orientation and its two clips.
 *
 * Karambit and butterfly are hand-built (several moving parts). Everything else is a one-piece knife described by a
 * {@link FixedKnives.Spec}: to add another one, write a factory in FixedKnives and add it to {@code FixedKnives.all()}.
 */
final class KnifeSkin {
    static final String KARAMBIT = "Karambit";
    static final String BUTTERFLY = "Butterfly";
    /** Selector order: the two hand-built knives first, then the katana and the CS2 set. */
    static final String[] NAMES = names();

    private static String[] names() {
        FixedKnives.Spec[] fixed = FixedKnives.all();
        String[] out = new String[2 + fixed.length];
        out[0] = KARAMBIT;
        out[1] = BUTTERFLY;
        for (int i = 0; i < fixed.length; i++) out[2 + i] = fixed[i].name;
        return out;
    }

    /** Channels: X Y Z YAW PITCH ROLL FLIP SPIN PART_A PART_B SHINE  (see InspectAnimation). */
    private static final float[][] KARAMBIT_INSPECT = {
            // t     X       Y       Z      YAW    PITCH  ROLL   FLIP   SPIN  PART_A  PART_B SHINE
            {0.00f,  0.0f,    0.0f,   0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f,  0.0f, 0.0f},
            // lift the knife toward the camera and tilt it while the hand slides up to the ring
            {0.06f, -0.09f,   0.012f, 0.03f, 4.0f,  8.0f,  6.0f,  0.0f,  0.5f,  -18.0f, 0.0f, 0.0f},
            // ring spin (loop window 0.14 .. 0.38: hold the inspect key to keep spinning; 2 turns per loop)
            {0.14f, -0.224f,  0.024f, 0.06f, -10.0f, 14.0f, 10.0f, 0.0f, 1.0f,  0.0f,   0.0f, 0.0f},
            {0.20f, -0.224f,  0.026f, 0.06f, -10.0f, 14.0f, 15.0f, 0.0f, 1.0f,  180.0f, 0.0f, 0.0f},
            {0.26f, -0.224f,  0.024f, 0.06f, -10.0f, 14.0f, 10.0f, 0.0f, 1.0f,  360.0f, 0.0f, 0.0f},
            {0.32f, -0.224f,  0.026f, 0.06f, -10.0f, 14.0f, 15.0f, 0.0f, 1.0f,  540.0f, 0.0f, 0.0f},
            {0.38f, -0.224f,  0.024f, 0.06f, -10.0f, 14.0f, 10.0f, 0.0f, 1.0f,  720.0f, 0.0f, 0.0f},
            // spin winds down smoothly (905 / 1015 / 1065 / 1080: each step smaller than the last, so the ring never slows and
            // speeds up again or creeps backwards), while the knife pops up and rolls over once around its blade
            {0.46f, -0.20f,   0.03f,  0.07f, -6.0f, 10.0f,  8.0f,  0.0f,  1.0f,  905.0f, 0.0f, 0.0f},
            {0.54f, -0.16f,   0.075f, 0.06f, 8.0f,  6.0f,  -10.0f, 180.0f, 1.0f, 1015.0f, 0.0f, 0.0f},
            {0.62f, -0.14f,   0.07f,  0.05f, 14.0f, 4.0f,  -24.0f, 360.0f, 1.0f, 1065.0f, 0.0f, 0.0f},
            // catch it and present the blade: tilt it to the light and sweep the shine along the edge
            {0.72f, -0.12f,   0.02f,  0.04f, -26.0f, 6.0f, -40.0f, 360.0f, 0.6f, 1080.0f, 0.0f, 0.4f},
            {0.80f, -0.08f,  -0.02f,  0.03f, -14.0f, 3.0f, -18.0f, 360.0f, 0.2f, 1080.0f, 0.0f, 0.95f},
            {0.90f, -0.03f,  -0.006f, 0.01f, -4.0f,  1.0f, -4.0f,  360.0f, 0.0f, 1080.0f, 0.0f, 1.0f},
            {1.00f,  0.0f,    0.0f,   0.0f,  0.0f,   0.0f,  0.0f,  360.0f, 0.0f, 1080.0f, 0.0f, 1.0f}};

    /** Draw: the knife is spun up from below with a few turns of the ring, then catches in the hand with a small shine. */
    private static final float[][] KARAMBIT_DEPLOY = {
            {0.00f,  0.03f, -0.20f, 0.02f, 0.0f, -35.0f, -25.0f, 0.0f, 1.0f, -720.0f, 0.0f, 0.0f},
            {0.50f,  0.0f,  -0.02f, 0.0f,  0.0f,   6.0f,   6.0f, 0.0f, 1.0f,  -90.0f, 0.0f, 0.0f},
            {0.72f,  0.0f,   0.0f,  0.0f,  0.0f,  -3.0f,  -3.0f, 0.0f, 0.35f,  14.0f, 0.0f, 0.0f},
            {0.88f,  0.0f,   0.0f,  0.0f,  0.0f,   1.0f,   1.0f, 0.0f, 0.1f,   -4.0f, 0.0f, 0.0f},
            {1.00f,  0.0f,   0.0f,  0.0f,  0.0f,   0.0f,   0.0f, 0.0f, 0.0f,    0.0f, 0.0f, 0.0f}};

    /**
     * Butterfly channels also use PART_A (blade) and PART_B (second handle). SLIDE stays 0: the fist holds the handle's tail
     * end for the whole clip, so the handle never swings back through the forearm when the clip ends.
     */
    private static final float[][] BUTTERFLY_INSPECT = {
            {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {0.08f, -0.07f, 0.09f, 0.05f, -8.0f, 8.0f, 10.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {0.14f, -0.09f, 0.12f, 0.06f, -12.0f, 8.0f, 14.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {0.19f, -0.09f, 0.12f, 0.06f, -10.0f, 10.0f, 2.0f, -18.0f, 0.0f, 90.0f, 180.0f, 0.0f, 0.0f},
            {0.24f, -0.09f, 0.13f, 0.06f, -12.0f, 8.0f, 10.0f, 0.0f, 0.0f, 180.0f, 360.0f, 0.0f, 0.0f},
            {0.29f, -0.09f, 0.12f, 0.06f, -10.0f, 10.0f, 2.0f, 18.0f, 0.0f, 270.0f, 540.0f, 0.0f, 0.0f},
            {0.34f, -0.09f, 0.12f, 0.06f, -12.0f, 8.0f, 14.0f, 0.0f, 0.0f, 360.0f, 720.0f, 0.0f, 0.0f},
            {0.39f, -0.09f, 0.12f, 0.06f, -10.0f, 10.0f, 2.0f, -18.0f, 0.0f, 450.0f, 900.0f, 0.0f, 0.0f},
            {0.44f, -0.09f, 0.13f, 0.06f, -12.0f, 8.0f, 10.0f, 0.0f, 0.0f, 540.0f, 1080.0f, 0.0f, 0.0f},
            {0.49f, -0.09f, 0.12f, 0.06f, -10.0f, 10.0f, 2.0f, 18.0f, 0.0f, 630.0f, 1260.0f, 0.0f, 0.0f},
            {0.54f, -0.09f, 0.12f, 0.06f, -12.0f, 8.0f, 14.0f, 0.0f, 0.0f, 720.0f, 1440.0f, 0.0f, 0.0f},
            {0.62f, -0.1f, 0.12f, 0.07f, -35.0f, 6.0f, 20.0f, 0.0f, 0.0f, 720.0f, 1440.0f, 0.0f, 0.0f},
            {0.76f, -0.1f, 0.13f, 0.07f, 25.0f, 6.0f, 16.0f, 0.0f, 0.0f, 720.0f, 1440.0f, 1.0f, 0.0f},
            {0.88f, -0.05f, 0.06f, 0.03f, 8.0f, 3.0f, 6.0f, 0.0f, 0.0f, 720.0f, 1440.0f, 1.0f, 0.0f},
            {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 720.0f, 1440.0f, 1.0f, 0.0f}};

    private static final float[][] BUTTERFLY_DEPLOY = {
            {0.0f, 0.02f, -0.14f, 0.02f, 0.0f, -30.0f, -20.0f, 0.0f, 0.0f, 180.0f, 0.0f, 0.0f, 0.0f},
            {0.3f, 0.01f, -0.04f, 0.01f, 0.0f, -10.0f, -10.0f, 0.0f, 0.0f, 220.0f, 100.0f, 0.0f, 0.0f},
            {0.62f, 0.0f, 0.01f, 0.0f, 0.0f, 4.0f, 6.0f, 0.0f, 0.0f, 350.0f, 340.0f, 0.0f, 0.0f},
            {0.8f, 0.0f, 0.0f, 0.0f, 0.0f, -2.0f, -2.0f, 0.0f, 0.0f, 365.0f, 368.0f, 0.0f, 0.0f},
            {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 360.0f, 360.0f, 0.0f, 0.0f}};

    private static final java.util.Map<String, KnifeSkin> CACHE = new java.util.HashMap<>();

    final String name;
    final InspectAnimation.Clip inspect;
    final InspectAnimation.Clip deploy;
    private final Part[] parts;
    private final Streak[] streaks;
    private final float voxel, gripX, gripY;
    private final Vector3f fist;
    private final Vector3f elbow = new Vector3f();
    private final Quaternionf rest;
    private final float shineTail;
    /**
     * Karambit only: model x where the hand holds the handle at rest (NaN = the hand stays on the grip point). The model is
     * anchored at the ring, so the ring spin pivots around the finger; while the SPIN channel is 1 the hand slides to the
     * ring and the forearm swings to {@link #spinForearm}, at rest the hand holds the middle of the handle (as in the reference).
     */
    private float holdX = Float.NaN;
    private final Vector3f bladeAxis = new Vector3f();
    private final Vector3f restForearm = new Vector3f();
    private final Vector3f spinForearm = new Vector3f();

    private KnifeSkin(String name, Part[] parts, Streak[] streaks, float voxel, float gripX, float gripY, Vector3f fist,
                      Vector3f blade, Vector3f spine, InspectAnimation.Clip inspect, InspectAnimation.Clip deploy,
                      Vector3f forearm, float shineTail) {
        this.name = name;
        this.parts = parts;
        this.streaks = streaks;
        this.voxel = voxel;
        this.gripX = gripX;
        this.gripY = gripY;
        this.fist = fist;
        this.inspect = inspect;
        this.deploy = deploy;
        this.shineTail = shineTail;
        // rest orientation: model +X along the blade, model +Y along the spine
        Vector3f x = new Vector3f(blade).normalize();
        Vector3f y = new Vector3f(spine).fma(-spine.dot(x), x).normalize();
        this.rest = new Quaternionf().setFromNormalized(new Matrix3f(x, y, new Vector3f(x).cross(y)));
        this.bladeAxis.set(x);
        this.spinForearm.set(forearm);
        this.restForearm.set(forearm);
        fist.fma(FirstPersonArm.ELBOW, forearm, this.elbow);
    }

    static KnifeSkin of(String name) {
        KnifeSkin skin = CACHE.get(name);
        if (skin != null) return skin;
        if (KARAMBIT.equals(name)) skin = karambit();
        else if (BUTTERFLY.equals(name)) skin = butterfly();
        else {
            for (FixedKnives.Spec spec : FixedKnives.all()) {
                if (spec.name.equals(name)) { skin = fixed(spec); break; }
            }
        }
        if (skin != null) CACHE.put(name, skin);
        return skin;
    }

    private static KnifeSkin karambit() {
        float ringX = KnifeModels.KARAMBIT_RING_X, ringY = KnifeModels.KARAMBIT_RING_Y;
        Part[] parts = {new Part(KnifeModels.karambit(), InspectAnimation.PART_A, ringX, ringY)};
        float[] tip = KnifeModels.karambitTip();
        Streak[] streaks = {new Streak(0, 1.0f, 0.0f, tip[0], tip[1], 1.0f)};
        // the model is anchored at its ring (the spin pivot); the hand holds the handle further down (holdX)
        KnifeSkin skin = new KnifeSkin(KARAMBIT, parts, streaks, KARAMBIT_VOXEL, ringX, ringY,
                new Vector3f(KARAMBIT_HOLD_RING), KARAMBIT_HOLD_BLADE, KARAMBIT_HOLD_SPINE,
                new InspectAnimation.Clip(KARAMBIT_INSPECT, 3.4f, 0.14f, 0.38f),
                new InspectAnimation.Clip(KARAMBIT_DEPLOY, 0.95f), KARAMBIT_FOREARM, 0.0f);
        skin.holdX = KnifeModels.KARAMBIT_HOLD_X;
        skin.restForearm.set(KARAMBIT_HOLD_FOREARM).normalize();
        return skin;
    }

    // ---- karambit rest pose, matched to the reference screenshot (right-hand item frame: +x right, +y up, +z toward you;
    // the left hand is the mirror image). Seen from the front: finger ring at the top, handle running down and away from the
    // forearm, the blade curling back up with the cutting edge on top. The pose is flat to the screen, so the ring spin
    // happens in the plane facing you and never swings the point into the camera.
    /** One model voxel in blocks (the model is chunky: 1 voxel per unit). */
    private static final float KARAMBIT_VOXEL = 0.0213f;
    /** Where the ring (the model's anchor) sits. */
    private static final Vector3f KARAMBIT_HOLD_RING = new Vector3f(-0.058f, 0.622f, 0.068f);
    /** Model +x (handle to blade) and +y (spine side) in the item frame, 218 degrees around the view axis. */
    private static final Vector3f KARAMBIT_HOLD_BLADE = new Vector3f(-0.903f, -0.429f, -0.027f);
    private static final Vector3f KARAMBIT_HOLD_SPINE = new Vector3f(0.418f, -0.892f, 0.174f);
    /** Extra offset of the forearm while holding (item frame): slides it back and behind the knife so the handle lies in front of the arm, as in the reference. */
    private static final Vector3f KARAMBIT_ARM_SHIFT = new Vector3f(0.20f, -0.22f, -0.10f);
    /** Direction from the hand back to the elbow while holding: the forearm comes in from the lower right, toward you. */
    private static final Vector3f KARAMBIT_HOLD_FOREARM = new Vector3f(0.60f, -0.62f, 0.50f);

    // pose shared by the other ring knives (Talon, Paracord, Skeleton): handle forward, spine up, forearm toward you
    /**
     * The ring spin happens in the plane facing you (the blade points left, the spine up), so the point never swings into
     * the camera, and the forearm comes in toward you, out of that plane, so the spin never goes through it.
     */
    private static final Vector3f KARAMBIT_FOREARM = new Vector3f(0.20f, -0.50f, 0.84f).normalize();
    private static final Vector3f KARAMBIT_FIST = new Vector3f(-0.10f, 0.16f, 0.02f);
    private static final Vector3f KARAMBIT_BLADE = new Vector3f(-0.96f, 0.10f, -0.26f);
    private static final Vector3f KARAMBIT_SPINE = new Vector3f(0.10f, 1.00f, 0.00f);

    /** Three parts around one hinge: fixed handle, blade (PART_A) and the second handle (PART_B). */
    private static KnifeSkin butterfly() {
        float pivotX = -1.5f, pivotY = 0.5f;
        Part[] parts = {
                new Part(KnifeModels.butterflyHandle(1), -1, pivotX, pivotY),
                new Part(KnifeModels.butterflyBlade(), InspectAnimation.PART_A, pivotX, pivotY),
                new Part(KnifeModels.butterflyHandle(-1), InspectAnimation.PART_B, pivotX, pivotY)};
        Streak[] streaks = {
                new Streak(1, 2.5f, 0.5f, 21.5f, -0.5f, 1.0f),
                new Streak(2, -4.0f, 0.5f, -27.5f, 0.5f, 0.8f)};
        return new KnifeSkin(BUTTERFLY, parts, streaks, 0.021f, BUTTERFLY_GRIP_X, 0.5f,
                BUTTERFLY_FIST, BUTTERFLY_BLADE, BUTTERFLY_SPINE,
                new InspectAnimation.Clip(BUTTERFLY_INSPECT, 3.8f, 0.14f, 0.54f),
                new InspectAnimation.Clip(BUTTERFLY_DEPLOY, 0.9f), FIXED_FOREARM, 0.0f);
    }

    /** Model x that sits in the fist. Near the end of the handles so the tail does not run along (and through) the forearm. */
    private static final float BUTTERFLY_GRIP_X = -27.0f;

    // first-person rest pose of the butterfly (item frame: +x right, +y up, +z toward you)
    private static final Vector3f BUTTERFLY_FIST = new Vector3f(-0.10f, 0.12f, 0.02f);
    private static final Vector3f BUTTERFLY_BLADE = new Vector3f(-0.60f, 0.70f, -0.30f);
    private static final Vector3f BUTTERFLY_SPINE = new Vector3f(-0.70f, -0.50f, 0.30f);

    /** Hand and elbow positions (item frame) for the given animation channels: used to keep the knife clear of the arm. */
    Vector3f handAt(float[] at, float weight) {
        return new Vector3f(fist).add(at[0] * weight, at[1] * weight, at[2] * weight);
    }

    Vector3f elbowPoint() { return new Vector3f(elbow); }

    // ---------------------------------------------------------------- one-piece knives

    /** The forearm enters from the right, so the knife is not hidden behind the hand block (the arm model is 0.25 wide). */
    private static final Vector3f FIXED_FOREARM = new Vector3f(0.90f, -0.40f, 0.10f).normalize();
    private static final Vector3f FIXED_FIST = new Vector3f(-0.10f, 0.14f, 0.02f);
    private static final Vector3f FIXED_BLADE = new Vector3f(-0.50f, 0.62f, -0.60f);
    private static final Vector3f FIXED_SPINE = new Vector3f(0.55f, 0.75f, 0.25f);

    /** Present the knife, turn it over, turn it back, look down the edge. Same channel layout as the karambit clip. */
    private static final float[][] FIXED_INSPECT = {
            {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {0.12f, -0.08f, 0.10f, 0.04f, -8.0f, 8.0f, 10.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {0.32f, -0.10f, 0.13f, 0.05f, 6.0f, 12.0f, 16.0f, 180.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {0.52f, -0.10f, 0.13f, 0.05f, 10.0f, 8.0f, -8.0f, 360.0f, 0.0f, 0.0f, 0.0f, 0.6f},
            {0.70f, -0.08f, 0.10f, 0.04f, -20.0f, 8.0f, -38.0f, 360.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {0.86f, -0.04f, 0.05f, 0.02f, -6.0f, 3.0f, 6.0f, 360.0f, 0.0f, 0.0f, 0.0f, 1.0f},
            {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 360.0f, 0.0f, 0.0f, 0.0f, 1.0f}};

    private static final float[][] FIXED_DEPLOY = {
            {0.0f, 0.02f, -0.14f, 0.02f, 0.0f, -30.0f, -20.0f, -270.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {0.55f, 0.0f, 0.0f, 0.0f, 0.0f, 4.0f, 4.0f, -40.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {0.78f, 0.0f, 0.0f, 0.0f, 0.0f, -2.0f, -2.0f, 6.0f, 0.0f, 0.0f, 0.0f, 0.0f},
            {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}};

    private static KnifeSkin fixed(FixedKnives.Spec spec) {
        float baseX = spec.blade * 0.35f;
        Part[] parts;
        Streak[] streaks;
        if (spec.folding) {                          // handle + blade on a hinge: part A swings the blade
            parts = new Part[]{
                    new Part(FixedKnives.build(spec, FixedKnives.HANDLE_ONLY), -1, 0.0f, 0.0f),
                    new Part(FixedKnives.build(spec, FixedKnives.BLADE_ONLY), InspectAnimation.PART_A, FixedKnives.HINGE_X, spec.hingeY())};
            streaks = new Streak[]{new Streak(1, baseX, spec.bladeMidY(baseX), spec.blade, spec.bladeEndY(), 1.0f)};
        } else if (spec.ring) {                      // the whole knife spins around its finger ring (part A)
            parts = new Part[]{new Part(FixedKnives.build(spec), InspectAnimation.PART_A, spec.ringX(), spec.ringY())};
            streaks = new Streak[]{new Streak(0, baseX, spec.bladeMidY(baseX), spec.blade, spec.bladeEndY(), 1.0f)};
        } else {
            parts = new Part[]{new Part(FixedKnives.build(spec), -1, 0.0f, 0.0f)};
            streaks = new Streak[]{new Streak(0, baseX, spec.bladeMidY(baseX), spec.blade, spec.bladeEndY(), 1.0f)};
        }
        KnifeClips.Set clips = KnifeClips.of(spec.name);
        InspectAnimation.Clip inspect = clips == null ? new InspectAnimation.Clip(FIXED_INSPECT, 3.0f)
                : new InspectAnimation.Clip(clips.inspect(), clips.inspectSeconds(), clips.loopFrom(), clips.loopTo());
        InspectAnimation.Clip deploy = new InspectAnimation.Clip(clips == null ? FIXED_DEPLOY : clips.deploy(), clips == null ? 0.7f : clips.deploySeconds());
        // ring knives are held like the karambit: handle forward, spine up, forearm coming in from the right
        boolean ring = spec.ring;
        return new KnifeSkin(spec.name, parts, streaks, spec.voxel, spec.gripX(), spec.gripY(),
                new Vector3f(ring ? KARAMBIT_FIST : FIXED_FIST), ring ? KARAMBIT_BLADE : FIXED_BLADE, ring ? KARAMBIT_SPINE : FIXED_SPINE,
                inspect, deploy, ring ? KARAMBIT_FOREARM : FIXED_FOREARM, spec.shineTail);
    }

    int streakCount() { return streaks.length; }
    float streakStrength(int i) { return streaks[i].strength; }

    /**
     * Draws arm + knife. {@code pose} is the first-person item frame. {@code at} = sampled clip channels, {@code weight}
     * = how much of them to apply (1 = fully animated, 0 = plain held pose).
     */
    void draw(PoseStack pose, float[] at, float weight, boolean slim, float size, Sink sink, float[] trail) {
        Matrix4f knife = knifeMatrix(at, weight, size, new Matrix4f());

        Vector3f hand = new Vector3f(fist).add(at[0] * weight, at[1] * weight, at[2] * weight);
        Vector3f elbowNow = elbow;
        if (!Float.isNaN(holdX)) {
            // 0 = holding the middle of the handle, 1 = hand at the ring (spin / draw), forearm swinging out of the spin plane
            float spin = Math.max(0.0f, Math.min(1.0f, at[InspectAnimation.SPIN] * weight));
            float hold = (holdX - gripX) * voxel * size;
            hand.fma(hold * (1.0f - spin), bladeAxis);
            hand.fma(1.0f - spin, KARAMBIT_ARM_SHIFT);
            Vector3f holdElbow = new Vector3f(fist).fma(hold, bladeAxis).add(KARAMBIT_ARM_SHIFT).fma(FirstPersonArm.ELBOW, restForearm);
            Vector3f spinElbow = new Vector3f(fist).fma(FirstPersonArm.ELBOW, spinForearm);
            elbowNow = holdElbow.lerp(spinElbow, spin);
        }
        FirstPersonArm.place(pose, hand, elbowNow, slim, posed -> sink.arm(posed));

        float sweep = shinePosition(at[InspectAnimation.SHINE]);
        KnifeMesh.Shine shine = new KnifeMesh.Shine(sweep, sweep > 0.0f ? (float) Math.sin(Math.PI * sweep) * weight : 0.0f, shineTail);

        if (trail != null) streakEnds(knife, at, weight, trail);

        pose.pushPose();
        pose.mulPose(knife);
        for (Part part : parts) {
            pose.pushPose();
            if (part.channel >= 0) {
                float angle = wrap(at[part.channel]) * weight;
                if (angle != 0.0f) pose.rotateAround(Axis.ZP.rotationDegrees(angle), part.pivotX, part.pivotY, 0.5f);
            }
            sink.part(pose, part.mesh, shine);
            pose.popPose();
        }
        pose.popPose();
    }

    /** Base + tip of every streak in item-frame coordinates: 6 floats per streak (base xyz, tip xyz). */
    private void streakEnds(Matrix4f knife, float[] at, float weight, float[] out) {
        Vector3f p = new Vector3f();
        for (int s = 0; s < streaks.length; s++) {
            Streak st = streaks[s];
            Part part = parts[st.part];
            Matrix4f m = new Matrix4f(knife);
            if (part.channel >= 0) {
                float angle = wrap(at[part.channel]) * weight;
                if (angle != 0.0f) m.rotateAround(Axis.ZP.rotationDegrees(angle), part.pivotX, part.pivotY, 0.5f);
            }
            m.transformPosition(st.baseX, st.baseY, 0.5f, p);
            out[s * 6] = p.x; out[s * 6 + 1] = p.y; out[s * 6 + 2] = p.z;
            m.transformPosition(st.tipX, st.tipY, 0.5f, p);
            out[s * 6 + 3] = p.x; out[s * 6 + 4] = p.y; out[s * 6 + 5] = p.z;
        }
    }

    /** Knife-model -> item-frame matrix for one frame. */
    Matrix4f knifeMatrix(float[] at, float weight, float size, Matrix4f dest) {
        float turn = (float) Math.PI / 180f * weight;
        float scale = voxel * size;
        dest.translation(fist.x + at[0] * weight, fist.y + at[1] * weight, fist.z + at[2] * weight)
                .rotate(new Quaternionf().rotateY(wrap(at[InspectAnimation.YAW]) * turn)
                        .rotateX(wrap(at[InspectAnimation.PITCH]) * turn)
                        .rotateZ(wrap(at[InspectAnimation.ROLL]) * turn))
                .rotate(rest);
        float flip = wrap(at[InspectAnimation.FLIP]) * turn;
        if (flip != 0.0f) dest.rotateX(flip);
        return dest.scale(scale).translate(at[InspectAnimation.SLIDE] * weight - gripX, -gripY, -0.5f);
    }

    /** Sawtooth of period 2: the first half sweeps 0..1 along the blade, the second half is dark. */
    static float shinePosition(float channel) {
        if (channel <= 0.0f) return 0.0f;
        float f = channel - 2.0f * (float) Math.floor(channel / 2.0f);
        return f < 1.0f ? f : 0.0f;
    }

    static float wrap(float degrees) {
        float v = degrees % 360.0f;
        if (v >= 180.0f) v -= 360.0f;
        if (v < -180.0f) v += 360.0f;
        return v;
    }

    private record Streak(int part, float baseX, float baseY, float tipX, float tipY, float strength) {}

    private record Part(KnifeMesh mesh, int channel, float pivotX, float pivotY) {}

    /** What the renderer has to provide: how to submit a mesh and how to draw the arm. */
    interface Sink {
        void part(PoseStack pose, KnifeMesh mesh, KnifeMesh.Shine shine);

        void arm(PoseStack pose);
    }
}
