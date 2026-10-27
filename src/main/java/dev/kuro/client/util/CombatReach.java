package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla melee reach: at most 3.0 blocks from the player's EYES to the closest point of the target's hitbox.
 * Deliberately ignores the Reach module, so axe / mace hits never try to reach farther than vanilla.
 */
public final class CombatReach {
    public static final double MAX = 3.0;

    private CombatReach() {}

    /** Distance from the eyes to the nearest point of the entity's hitbox (0 when the eyes are inside it). */
    public static double eyeDistance(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || e == null) return Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition();
        AABB b = e.getBoundingBox();
        double dx = Math.max(Math.max(b.minX - eye.x, 0), eye.x - b.maxX);
        double dy = Math.max(Math.max(b.minY - eye.y, 0), eye.y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - eye.z, 0), eye.z - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static boolean inReach(Entity e) { return inReach(e, MAX); }

    /** "limit" is clamped to the vanilla 3.0. */
    public static boolean inReach(Entity e, double limit) { return eyeDistance(e) <= Math.min(limit, MAX); }

    /** True when a ray from the eyes along (yaw, pitch), at most "limit" (max 3.0) long, hits the entity's hitbox. */
    public static boolean rayHits(Entity e, float yaw, float pitch, double limit) { return rayHits(e, yaw, pitch, limit, 0.0); }

    /** Same, with the hitbox grown by "margin" blocks (used for the mace follow-up, after the axe knocked the target back). */
    /** Ray of exactly "len" blocks, NOT clamped to 3.0 (only for aiming ahead of a fast fall; the hit itself is still checked at 3.0). */
    public static boolean rayHitsLen(Entity e, float yaw, float pitch, double len) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || e == null) return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(len));
        AABB box = e.getBoundingBox();
        return box.contains(eye) || box.clip(eye, end).isPresent();
    }

    public static boolean rayHits(Entity e, float yaw, float pitch, double limit, double margin) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || e == null) return false;
        double len = Math.min(limit, MAX);
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(len));
        AABB box = margin > 0 ? e.getBoundingBox().inflate(margin) : e.getBoundingBox();
        return box.contains(eye) || box.clip(eye, end).isPresent();
    }
}
