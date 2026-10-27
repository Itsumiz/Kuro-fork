package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Smoothly turns your REAL view toward an entity, one step per call (call it once per tick). Each step moves a share of
 * the remaining angle (so it eases out as it arrives), at least 1 degree and at most maxStep degrees, per axis.
 */
public final class SmoothLook {
    private SmoothLook() {}

    public static void step(Entity target, double maxStep) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || target == null) return;
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.getX() - eye.x, dz = target.getZ() - eye.z;
        double dy = target.getY() + target.getBbHeight() * 0.7 - eye.y;
        double h = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = h > 0.35 ? (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0) : mc.player.getYRot();
        float wantPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(h, 1.0E-4))), -90.0, 90.0);
        mc.player.setYRot(mc.player.getYRot() + ease(Mth.wrapDegrees(wantYaw - mc.player.getYRot()), maxStep));
        mc.player.setXRot(Mth.clamp(mc.player.getXRot() + ease(wantPitch - mc.player.getXRot(), maxStep), -90f, 90f));
    }

    private static float ease(float diff, double maxStep) {
        float a = Math.abs(diff);
        if (a < 0.05f) return diff;
        float s = Math.min(a, (float) Math.min(maxStep, Math.max(a * 0.35f, 1.0f)));
        return Math.copySign(s, diff);
    }
}
