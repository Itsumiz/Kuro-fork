package dev.kuro.client.echo.utils.combat;

import dev.kuro.client.echo.Imports;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class ExplosionUtils implements Imports {

    private ExplosionUtils() {
    }

    public static float getSeenPercent(Vec3 explosionCenter, AABB targetBox, Entity raycastContext) {
        return getSeenPercent(explosionCenter, targetBox, raycastContext, null);
    }

    private static float getSeenPercent(Vec3 explosionCenter, AABB targetBox, Entity raycastContext, BlockPos ignoredBlockPos) {
        if (explosionCenter == null || targetBox == null || raycastContext == null || mc.level == null) {
            return 0.0F;
        }

        double xStep = 1.0 / (targetBox.getXsize() * 2.0 + 1.0);
        double yStep = 1.0 / (targetBox.getYsize() * 2.0 + 1.0);
        double zStep = 1.0 / (targetBox.getZsize() * 2.0 + 1.0);
        if (xStep < 0.0 || yStep < 0.0 || zStep < 0.0) {
            return 0.0F;
        }

        double xOffset = (1.0 - Math.floor(1.0 / xStep) * xStep) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zStep) * zStep) / 2.0;

        int clearRays = 0;
        int totalRays = 0;

        for (double x = 0.0; x <= 1.0; x += xStep) {
            for (double y = 0.0; y <= 1.0; y += yStep) {
                for (double z = 0.0; z <= 1.0; z += zStep) {
                    Vec3 sample = new Vec3(
                        Mth.lerp(x, targetBox.minX, targetBox.maxX) + xOffset,
                        Mth.lerp(y, targetBox.minY, targetBox.maxY),
                        Mth.lerp(z, targetBox.minZ, targetBox.maxZ) + zOffset
                    );

                    if (isRayClear(sample, explosionCenter, raycastContext, ignoredBlockPos)) {
                        clearRays++;
                    }
                    totalRays++;
                }
            }
        }

        return totalRays == 0 ? 0.0F : (float) clearRays / totalRays;
    }

    private static boolean isRayClear(Vec3 sample, Vec3 explosionCenter, Entity raycastContext, BlockPos ignoredBlockPos) {
        HitResult hit = mc.level.clip(new ClipContext(
            sample,
            explosionCenter,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            raycastContext
        ));
        if (hit.getType() == HitResult.Type.MISS) return true;
        return ignoredBlockPos != null
                && hit instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(ignoredBlockPos);
    }

    /**
     * Estimates the damage a charged respawn anchor at {@code anchorPos} would
     * deal to {@code victim} if it detonated right now. Mirrors vanilla
     * {@code ExplosionDamageCalculator.getEntityDamageAmount} (radius 5.0,
     * vanilla `(e^2 + e)/2 * 7 * 2r + 1` formula) and then applies vanilla
     * armor absorption via {@link net.minecraft.world.damagesource.CombatRules}'s
     * weapon-less branch (explosions carry no weapon item). Blast Protection
     * isn't included -- it's data-driven and needs a ServerLevel handle -- so
     * the value is the no-protection-enchant ceiling. Tune the threshold with
     * that in mind. Vanilla removes the anchor block before calculating the
     * explosion, so exposure rays ignore {@code anchorPos}.
     */
    public static float getAnchorDamageTo(LivingEntity victim, BlockPos anchorPos) {
        if (victim == null || anchorPos == null || mc.level == null) return 0.0f;
        return getExplosionDamageTo(victim, Vec3.atCenterOf(anchorPos), 5.0f, anchorPos);
    }

    public static float getExplosionDamageTo(LivingEntity victim, Vec3 explosionCenter, float radius) {
        return getExplosionDamageTo(victim, explosionCenter, radius, null);
    }

    private static float getExplosionDamageTo(LivingEntity victim, Vec3 explosionCenter, float radius, BlockPos ignoredBlockPos) {
        if (victim == null || explosionCenter == null || mc.level == null) return 0.0f;

        float diameter = radius * 2.0f;
        double dist = Math.sqrt(victim.distanceToSqr(explosionCenter)) / diameter;
        if (dist > 1.0) return 0.0f;

        float exposure = getSeenPercent(explosionCenter, victim.getBoundingBox(), victim, ignoredBlockPos);
        double e = (1.0 - dist) * exposure;
        float rawDamage = (float) ((e * e + e) / 2.0 * 7.0 * diameter + 1.0);

        float armor = (float) victim.getArmorValue();
        float toughness = (float) victim.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float divider = 2.0f + toughness / 4.0f;
        float effectiveArmor = Mth.clamp(armor - rawDamage / divider, armor * 0.2f, 20.0f);
        return rawDamage * (1.0f - effectiveArmor / 25.0f);
    }
}
