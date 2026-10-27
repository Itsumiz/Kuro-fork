package dev.kuro.client.echo.handlers;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side rotation state (what the movement packet carries) and the silent-rotation flag.
 * Port of hack.echo.client.handlers.RotationHandler. The auth gate in MathProt.getEnforcedPitch is not ported:
 * pitch is only clamped to -90..90.
 *
 * Fed by dev.kuro.client.mixin.ClientPlayerEntityMixin (sendPosition): the echo EventMove.Pre carries these values.
 */
public class RotationHandler {
    private static float serverYaw = 0f;
    private static float serverPitch = 0f;
    private static float prevServerYaw = 0f;
    private static float prevServerPitch = 0f;
    private static float serverDeltaYaw = 0f;
    private static boolean hasSilentRotation = false;

    public static float getServerYaw() { return serverYaw; }
    public static void setServerYaw(float v) { serverYaw = v; }
    public static float getServerPitch() { return serverPitch; }
    public static void setServerPitch(float v) { serverPitch = v; }
    public static float getPrevServerYaw() { return prevServerYaw; }
    public static float getPrevServerPitch() { return prevServerPitch; }
    public static float getServerDeltaYaw() { return serverDeltaYaw; }
    public static void setServerDeltaYaw(float v) { serverDeltaYaw = v; }
    public static boolean isHasSilentRotation() { return hasSilentRotation; }
    public static void setHasSilentRotation(boolean v) { hasSilentRotation = v; }

    public static float getEnforcedPitch(float requestedPitch) {
        return Mth.clamp(requestedPitch, -90f, 90f);
    }

    /** Update server rotations (called every tick to keep them in sync); keeps yaw continuous (AimModulo360). */
    public static void updateServerRotations(float yaw, float pitch) {
        prevServerYaw = serverYaw;
        prevServerPitch = serverPitch;
        serverYaw = serverYaw + Mth.wrapDegrees(yaw - serverYaw);
        serverPitch = Mth.clamp(getEnforcedPitch(pitch), -90f, 90f);
        serverDeltaYaw = 0f;
    }

    /** Set server rotations with delta tracking for silent aim. */
    public static void setServerRotationsWithDelta(float yaw, float pitch) {
        prevServerYaw = serverYaw;
        prevServerPitch = serverPitch;
        serverDeltaYaw = Mth.wrapDegrees(yaw - serverYaw);
        serverYaw = yaw;
        serverPitch = Mth.clamp(getEnforcedPitch(pitch), -90f, 90f);
    }

    public static void reset() {
        serverDeltaYaw = 0f;
        hasSilentRotation = false;
    }

    public static void syncToPlayer(float playerYaw, float playerPitch) {
        prevServerYaw = serverYaw;
        prevServerPitch = serverPitch;
        serverYaw = serverYaw + Mth.wrapDegrees(playerYaw - serverYaw);
        serverPitch = Mth.clamp(getEnforcedPitch(playerPitch), -90f, 90f);
        serverDeltaYaw = 0f;
        hasSilentRotation = false;
    }

    public static Vec3 getRotationVector(float pitch, float yaw) {
        float pitchRad = pitch * Mth.DEG_TO_RAD;
        float yawRad = -yaw * Mth.DEG_TO_RAD;
        float cosPitch = Mth.cos(pitchRad);
        float sinPitch = Mth.sin(pitchRad);
        float cosYaw = Mth.cos(yawRad);
        float sinYaw = Mth.sin(yawRad);
        return new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }
}
