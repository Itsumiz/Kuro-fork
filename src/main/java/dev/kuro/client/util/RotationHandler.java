package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

public class RotationHandler {
    private static boolean silentActive = false;
    private static float silentYaw = 0;
    private static float silentPitch = 0;

    public static boolean isSilentActive() { return silentActive; }
    public static float getSilentYaw() { return silentYaw; }
    public static float getSilentPitch() { return silentPitch; }

    public static void setSilent(float yaw, float pitch) {
        silentYaw = yaw;
        silentPitch = Mth.clamp(pitch, -90f, 90f);
        silentActive = true;
    }

    public static void clear() {
        silentActive = false;
    }

    public static float getAngleDifference(float a, float b) {
        return ((((a - b) % 360F) + 540F) % 360F) - 180F;
    }
}