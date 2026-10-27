package dev.kuro.client.echo.utils.rotation;

import dev.kuro.client.echo.Imports;
import dev.kuro.client.echo.event.EventSubscribe;
import dev.kuro.client.echo.handlers.RotationHandler;
import dev.kuro.client.module.Module;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Rotation control for the ported combat code: the part of hack.echo.client.utils.rotation.RotationUtils that position-based aiming
 * uses (rotateTo(Vec3), rotateBack, priority based control, silent rotation, GCD fix, WindMouse, raycast helpers).
 * Not ported: aiming at entities (hitbox point search, multipoint, prediction) and syncToClientAndRun / forceSilentRotations,
 * which only entity aimers and the knockback modules use.
 */
public final class RotationUtils implements Imports {

    private RotationUtils() {}

    // -- Enums --

    public enum AimType {
        REGULAR,
        BLATANT,
        WINDMOUSE
    }

    // -- State --

    private static float[] lastRotations = new float[]{0f, 0f};
    private static float[] lastSentRotations = new float[]{0f, 0f};

    private static double windYawVel = 0;
    private static double windPitchVel = 0;
    private static double windYaw = 0;
    private static double windPitch = 0;
    private static float gcdYawRemainder = 0f;
    private static float gcdPitchRemainder = 0f;

    private static long lastNanoTime = 0;

    private static boolean isTracking = false;

    private static Module currentController = null;
    private static EventSubscribe.Priority currentPriority = EventSubscribe.Priority.LOWEST;

    public static boolean isTracking() { return isTracking; }

    // -- Delta time (MathUtil.getDeltaSeconds: 1/60 on the first call, clamped to 0.1 s) --

    private static float getDeltaSeconds() {
        long now = System.nanoTime();
        float delta = lastNanoTime == 0 ? 1.0f / 60.0f : (now - lastNanoTime) / 1_000_000_000.0f;
        delta = Math.min(delta, 0.1f);
        lastNanoTime = now;
        return delta;
    }

    // -- State management --

    public static void resetState() {
        if (mc.player != null) {
            lastRotations[0] = mc.player.getYRot();
            lastRotations[1] = mc.player.getXRot();
            lastSentRotations[0] = mc.player.getYRot();
            lastSentRotations[1] = mc.player.getXRot();
            RotationHandler.syncToPlayer(mc.player.getYRot(), mc.player.getXRot());
        } else {
            lastRotations = new float[]{0f, 0f};
            lastSentRotations = new float[]{0f, 0f};
            RotationHandler.reset();
        }
        windYawVel = 0;
        windPitchVel = 0;
        windYaw = 0;
        windPitch = 0;
        gcdYawRemainder = 0f;
        gcdPitchRemainder = 0f;
        lastNanoTime = 0;
        isTracking = false;
        currentController = null;
        currentPriority = EventSubscribe.Priority.LOWEST;
    }

    public static void initFromPlayer() {
        if (mc.player == null) return;
        lastRotations[0] = mc.player.getYRot();
        lastRotations[1] = mc.player.getXRot();
        lastSentRotations[0] = mc.player.getYRot();
        lastSentRotations[1] = mc.player.getXRot();
        gcdYawRemainder = 0f;
        gcdPitchRemainder = 0f;
        RotationHandler.updateServerRotations(mc.player.getYRot(), mc.player.getXRot());
    }

    public static void stopTracking() {
        if (mc.player != null) {
            RotationHandler.syncToPlayer(mc.player.getYRot(), mc.player.getXRot());
            lastSentRotations[0] = mc.player.getYRot();
            lastSentRotations[1] = mc.player.getXRot();
        } else {
            RotationHandler.reset();
        }
        lastNanoTime = 0;
        isTracking = false;
        currentController = null;
        currentPriority = EventSubscribe.Priority.LOWEST;
    }

    public static float[] getLastRotations() {
        return lastRotations;
    }

    // -- Rotate to a position --

    public static boolean rotateTo(Vec3 position, float yawSpeed, float pitchSpeed,
                                   boolean silent, AimType aimType) {
        return rotateTo(position, yawSpeed, pitchSpeed, silent, aimType, null, EventSubscribe.Priority.NORMAL);
    }

    public static boolean rotateTo(Vec3 position, float yawSpeed, float pitchSpeed,
                                   boolean silent, AimType aimType, Module requester, EventSubscribe.Priority priority) {
        if (position == null || mc.player == null) return false;
        if (requester != null && !tryTakeControl(requester, priority)) return false;

        windYawVel = 0;
        windPitchVel = 0;
        windYaw = 0;
        windPitch = 0;

        if (!isTracking) initFromPlayer();
        isTracking = true;

        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float[] targetRot = calculate(mc.player.getEyePosition(pt), position);
        float[] baseRotation = silent ? lastRotations : new float[]{mc.player.getYRot(), mc.player.getXRot()};
        targetRot = ensureContinuity(baseRotation, targetRot);

        lastRotations = getFixedRotations(lastRotations,
                getTargetRotations(lastRotations, targetRot, yawSpeed, pitchSpeed, silent, aimType));

        applyRotations(lastRotations, silent);
        return true;
    }

    // -- Rotate back to client view --

    public static boolean rotateBack(boolean silent) {
        return rotateBack(silent, 150f, 150f, AimType.REGULAR);
    }

    public static boolean rotateBack(boolean silent, float yawSpeed, float pitchSpeed, AimType aimType) {
        return rotateBack(silent, yawSpeed, pitchSpeed, aimType, true);
    }

    public static boolean rotateBack(boolean silent, float yawSpeed, float pitchSpeed, AimType aimType, boolean scaleSpeed) {
        if (mc.player == null) {
            isTracking = false;
            currentController = null;
            currentPriority = EventSubscribe.Priority.LOWEST;
            return false;
        }

        if (!isTracking) {
            RotationHandler.syncToPlayer(mc.player.getYRot(), mc.player.getXRot());
            lastSentRotations[0] = mc.player.getYRot();
            lastSentRotations[1] = mc.player.getXRot();
            currentController = null;
            currentPriority = EventSubscribe.Priority.LOWEST;
            return false;
        }

        float[] clientRot = new float[]{mc.player.getYRot(), mc.player.getXRot()};

        if (aimType == AimType.BLATANT) {
            RotationHandler.syncToPlayer(mc.player.getYRot(), mc.player.getXRot());
            lastSentRotations[0] = mc.player.getYRot();
            lastSentRotations[1] = mc.player.getXRot();
            lastRotations[0] = mc.player.getYRot();
            lastRotations[1] = mc.player.getXRot();
            isTracking = false;
            currentController = null;
            currentPriority = EventSubscribe.Priority.LOWEST;
            return false;
        }

        float delta = getDeltaSeconds();
        float speedMultiplier = scaleSpeed ? (silent ? 2.5f : 0.5f) : 1.0f;

        lastRotations[0] = lerpAngle(lastRotations[0], clientRot[0], delta, yawSpeed * speedMultiplier);
        lastRotations[1] = lerpAngle(lastRotations[1], clientRot[1], delta, pitchSpeed * speedMultiplier);

        float yawDiff = Math.abs(Mth.wrapDegrees(lastRotations[0] - clientRot[0]));
        float pitchDiff = Math.abs(lastRotations[1] - clientRot[1]);

        if (yawDiff < 1.0f && pitchDiff < 1.0f) {
            RotationHandler.syncToPlayer(mc.player.getYRot(), mc.player.getXRot());
            lastSentRotations[0] = mc.player.getYRot();
            lastSentRotations[1] = mc.player.getXRot();
            isTracking = false;
            currentController = null;
            currentPriority = EventSubscribe.Priority.LOWEST;
            return false;
        }

        if (silent) applySilentRotations(lastRotations);
        return true;
    }

    // -- Rotation application --

    public static void applySilentRotations(float[] rotations) {
        float yawDiff = Math.abs(Mth.wrapDegrees(rotations[0] - lastSentRotations[0]));
        float enforcedPitch = RotationHandler.getEnforcedPitch(rotations[1]);
        float pitchDiff = Math.abs(enforcedPitch - lastSentRotations[1]);

        if (yawDiff <= 0.01f && pitchDiff <= 0.01f) return;

        float normalizedYaw = lastSentRotations[0] + Mth.wrapDegrees(rotations[0] - lastSentRotations[0]);
        float normalizedPitch = Mth.clamp(enforcedPitch, -90f, 90f);

        RotationHandler.setServerRotationsWithDelta(normalizedYaw, normalizedPitch);
        RotationHandler.setHasSilentRotation(true);
        lastSentRotations[0] = normalizedYaw;
        lastSentRotations[1] = normalizedPitch;
    }

    public static void applyClientRotations(float[] rotations) {
        if (mc.player == null) return;
        float enforcedPitch = RotationHandler.getEnforcedPitch(rotations[1]);
        mc.player.setYRot(rotations[0]);
        mc.player.setXRot(enforcedPitch);
        RotationHandler.reset();
    }

    private static void applyRotations(float[] rotations, boolean silent) {
        if (silent) {
            applySilentRotations(rotations);
        } else {
            applyClientRotations(rotations);
        }
    }

    // -- Angle calculation --

    public static float[] calculate(Vec3 from, Vec3 to) {
        Vec3 diff = to.subtract(from);
        double distance = Math.hypot(diff.x, diff.z);
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(diff.z, diff.x))) - 90.0F);
        float pitch = Mth.wrapDegrees((float) (-Math.toDegrees(Math.atan2(diff.y, distance))));
        return new float[]{yaw, pitch};
    }

    // -- Lerp helpers --

    public static float lerpAngle(float current, float target, float deltaSeconds, float speed) {
        float diff = Mth.wrapDegrees(target - current);
        float step = speed * deltaSeconds;
        if (Math.abs(diff) <= step) return current + diff;
        return current + Math.signum(diff) * step;
    }

    // -- Aim math selection --

    public static float[] getTargetRotations(float[] lastRot, float[] targetRot,
                                              float yawSpeed, float pitchSpeed,
                                              boolean silent, AimType aimType) {
        if (mc.player == null || mc.level == null) return lastRot;

        float yaw = silent ? lastRot[0] : mc.player.getYRot();
        float pitch = silent ? lastRot[1] : mc.player.getXRot();
        float delta = getDeltaSeconds();
        float speedMultiplier = silent ? 2.5f : 0.5f;

        return switch (aimType) {
            case REGULAR -> new float[]{
                    lerpAngle(yaw, targetRot[0], delta, yawSpeed * speedMultiplier),
                    lerpAngle(pitch, targetRot[1], delta, pitchSpeed * speedMultiplier)
            };
            case BLATANT -> new float[]{targetRot[0], targetRot[1]};
            case WINDMOUSE -> getWindMouseRotations(
                    new float[]{yaw, pitch}, targetRot, yawSpeed, pitchSpeed, delta, silent);
        };
    }

    // -- WindMouse --

    private static float[] getWindMouseRotations(float[] currentRot, float[] targetRot,
                                                  float yawSpeed, float pitchSpeed,
                                                  float delta, boolean silent) {
        double currentYaw = currentRot[0];
        double currentPitch = currentRot[1];
        double targetYaw = targetRot[0];
        double targetPitch = targetRot[1];

        boolean yawEnabled = yawSpeed > 0.0f;
        boolean pitchEnabled = pitchSpeed > 0.0f;

        double yawScale = yawSpeed * (silent ? 2.5 : 0.5);
        double pitchScale = pitchSpeed * (silent ? 2.5 : 0.5);

        double M0Yaw = yawScale * delta;
        double M0Pitch = pitchScale * delta;
        double baseM0 = Math.max(M0Yaw, M0Pitch);

        double G_0 = baseM0 * 0.6;
        double W_0 = baseM0 * 0.2;
        double D_0 = 8.0;

        double diffYaw = Mth.wrapDegrees(targetYaw - currentYaw);
        double diffPitch = targetPitch - currentPitch;
        double dist = Math.hypot(diffYaw, diffPitch);

        double W_mag = Math.min(W_0, dist);

        if (dist >= D_0) {
            windYaw = yawEnabled ? windYaw / Math.sqrt(3) + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * W_mag / Math.sqrt(5) : 0;
            windPitch = pitchEnabled ? windPitch / Math.sqrt(3) + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * W_mag / Math.sqrt(5) : 0;
        } else {
            windYaw = yawEnabled ? windYaw / Math.sqrt(3) : 0;
            windPitch = pitchEnabled ? windPitch / Math.sqrt(3) : 0;
        }

        double currM0Yaw = M0Yaw;
        double currM0Pitch = M0Pitch;
        if (dist < D_0) {
            currM0Yaw /= Math.sqrt(5);
            currM0Pitch /= Math.sqrt(5);
        }

        double gravity = Math.min(G_0, dist);

        if (yawEnabled) {
            windYawVel += windYaw + (dist == 0 ? 0 : gravity * diffYaw / dist);
        } else {
            windYawVel = 0;
        }
        if (pitchEnabled) {
            windPitchVel += windPitch + (dist == 0 ? 0 : gravity * diffPitch / dist);
        } else {
            windPitchVel = 0;
        }

        if (dist < 0.5) {
            windYawVel *= 0.7;
            windPitchVel *= 0.7;
        }
        if (dist < 0.1) {
            windYawVel = 0;
            windPitchVel = 0;
        }

        if (yawEnabled && currM0Yaw > 0) {
            double yawLimit = currM0Yaw * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5);
            windYawVel = Mth.clamp(windYawVel, -yawLimit, yawLimit);
        }
        if (pitchEnabled && currM0Pitch > 0) {
            double pitchLimit = currM0Pitch * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5);
            windPitchVel = Mth.clamp(windPitchVel, -pitchLimit, pitchLimit);
        }

        return new float[]{
                (float) (currentYaw + windYawVel),
                (float) (currentPitch + windPitchVel)
        };
    }

    // -- GCD fix --

    public static float[] getFixedRotations(float[] prev, float[] current) {
        float yawDiff = Mth.wrapDegrees(current[0] - prev[0]);
        float pitchDiff = Mth.wrapDegrees(current[1] - prev[1]);
        float[] capped = new float[]{prev[0] + yawDiff, prev[1] + pitchDiff};
        return applyGCD(capped, prev);
    }

    public static float[] applyGCD(float[] rotations, float[] lastRotations) {
        float f = (float) (mc.options.sensitivity().get() * 0.6F + 0.2F);
        float gcd = f * f * f * 1.2F;

        float accumulatedYaw = gcdYawRemainder + Mth.wrapDegrees(rotations[0] - lastRotations[0]);
        float accumulatedPitch = gcdPitchRemainder + Mth.wrapDegrees(rotations[1] - lastRotations[1]);
        float snappedYaw = gcd <= 0f ? accumulatedYaw : (int) (accumulatedYaw / gcd) * gcd;
        float snappedPitch = gcd <= 0f ? accumulatedPitch : (int) (accumulatedPitch / gcd) * gcd;

        gcdYawRemainder = Mth.wrapDegrees(accumulatedYaw - snappedYaw);
        gcdPitchRemainder = accumulatedPitch - snappedPitch;

        return new float[]{
                lastRotations[0] + snappedYaw,
                lastRotations[1] + snappedPitch
        };
    }

    public static float[] ensureContinuity(float[] lastRot, float[] targetRot) {
        float lastYaw = Mth.wrapDegrees(lastRot[0]);
        float diff = Mth.wrapDegrees(targetRot[0] - lastYaw);
        return new float[]{lastYaw + diff, Mth.clamp(targetRot[1], -90f, 90f)};
    }

    // -- Silent rotation accessors --

    public static float getSilentYaw() {
        return lastSentRotations[0];
    }

    public static float getSilentPitch() {
        return lastSentRotations[1];
    }

    public static boolean hasSilentRotation() {
        return RotationHandler.isHasSilentRotation();
    }

    // -- Priority system --

    public static boolean tryTakeControl(Module requester, EventSubscribe.Priority priority) {
        if (currentController == null || currentController == requester) {
            currentController = requester;
            currentPriority = priority;
            return true;
        }
        if (getPriorityValue(priority) > getPriorityValue(currentPriority)) {
            currentController = requester;
            currentPriority = priority;
            return true;
        }
        return false;
    }

    private static int getPriorityValue(EventSubscribe.Priority priority) {
        return switch (priority) {
            case HIGHEST -> 5;
            case HIGH -> 4;
            case NORMAL -> 3;
            case LOW -> 2;
            case LOWEST -> 1;
        };
    }

    public static boolean isControlledBy(Module feature) {
        return currentController == feature;
    }

    public static boolean canTakeControl(Module requester, EventSubscribe.Priority priority) {
        if (currentController == null || currentController == requester) return true;
        return getPriorityValue(priority) > getPriorityValue(currentPriority);
    }

    public static void releaseControl(Module requester) {
        if (currentController == requester) {
            currentController = null;
            currentPriority = EventSubscribe.Priority.LOWEST;
        }
    }

    /**
     * Cleanup for rotation tracking. Downgrades priority, rotates back, and releases control.
     * @return true if still rotating back (call again next tick), false when cleanup is complete
     */
    public static boolean cleanup(Module requester, boolean silent) {
        return cleanup(requester, silent, 150f, 150f, AimType.REGULAR, true);
    }

    public static boolean cleanup(Module requester, boolean silent, float yawSpeed, float pitchSpeed, AimType aimType, boolean scaleSpeed) {
        if (!isControlledBy(requester)) return false;
        tryTakeControl(requester, EventSubscribe.Priority.LOWEST);
        if (rotateBack(silent, yawSpeed, pitchSpeed, aimType, scaleSpeed)) return true;
        releaseControl(requester);
        return false;
    }

    // -- Builder --

    public static RotationBuilder aim(Module requester) {
        return new RotationBuilder(requester);
    }

    public static class RotationBuilder {
        private final Module requester;
        private EventSubscribe.Priority priority = EventSubscribe.Priority.NORMAL;
        private boolean silent = false;
        private AimType aimType = AimType.REGULAR;
        private float yawSpeed = 100f;
        private float pitchSpeed = 100f;

        RotationBuilder(Module requester) { this.requester = requester; }

        public RotationBuilder priority(EventSubscribe.Priority p) { this.priority = p; return this; }
        public RotationBuilder silent() { this.silent = true; return this; }
        public RotationBuilder silent(boolean s) { this.silent = s; return this; }
        public RotationBuilder windMouse() { this.aimType = AimType.WINDMOUSE; return this; }
        public RotationBuilder aimType(AimType t) { this.aimType = t; return this; }
        public RotationBuilder speed(float s) { this.yawSpeed = s; this.pitchSpeed = s; return this; }
        public RotationBuilder speed(float yaw, float pitch) { this.yawSpeed = yaw; this.pitchSpeed = pitch; return this; }

        public boolean to(Vec3 pos) {
            return rotateTo(pos, yawSpeed, pitchSpeed, silent, aimType, requester, priority);
        }
    }

    // -- Raycast helpers (used by interact-style modules to test what the player is currently aimed at) --

    /** Casts an OUTLINE ray from the player's eye in the given direction out to vanilla block-interaction range. */
    public static BlockHitResult raycastFromRotation(float yaw, float pitch, float partialTick) {
        if (mc.player == null || mc.level == null) return null;
        Vec3 eye = mc.player.getEyePosition(partialTick);
        Vec3 look = RotationHandler.getRotationVector(pitch, yaw);
        Vec3 end = eye.add(look.scale(mc.player.blockInteractionRange()));
        return mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
    }

    /** True if a ray from the eye at the given rotation passes through (a slightly inflated) {@code clickedPos}. */
    public static boolean rayIntersectsBlock(float yaw, float pitch, BlockPos clickedPos, float partialTick) {
        if (mc.player == null || clickedPos == null) return false;
        Vec3 eye = mc.player.getEyePosition(partialTick);
        Vec3 look = RotationHandler.getRotationVector(pitch, yaw);
        Vec3 end = eye.add(look.scale(mc.player.blockInteractionRange()));
        return new AABB(clickedPos).inflate(1.0E-4).clip(eye, end).isPresent();
    }
}
