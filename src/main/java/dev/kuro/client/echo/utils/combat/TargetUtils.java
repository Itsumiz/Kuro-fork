package dev.kuro.client.echo.utils.combat;

import dev.kuro.client.echo.Imports;
import dev.kuro.client.module.impl.AntiBot;
import dev.kuro.client.util.Friends;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Target selection for the ported combat code (hack.echo.client.utils.combat.TargetUtils).
 *
 * The echo client filters through its "Targets" module (entity types, teams, anti bot, friends, keep-target). Kuro has no such
 * module, so the filter here is fixed: other players only, alive, not a spectator, not a friend, not an Anti Bot bot. Keep-target
 * (the sticky / last-attacked target) is off, which is what resolveTarget does in the echo client when that option is disabled.
 */
public class TargetUtils implements Imports {

    private TargetUtils() {
    }

    public static boolean isTargetAllowed(LivingEntity entity) {
        if (entity == null) return false;
        if (!entity.isAlive() || entity.isRemoved() || entity.isDeadOrDying()) return false;
        if (mc.player == null) return false;
        if (entity instanceof ArmorStand) return false;
        if (!(entity instanceof Player player)) return false;
        if (player.isSpectator()) return false;
        if (Friends.is((Entity) player)) return false;
        return !AntiBot.isBotPlayer(player);
    }

    /** Keep-target is off, so the candidate is the target. */
    public static LivingEntity resolveTarget(LivingEntity candidate) {
        return candidate;
    }

    public static LivingEntity findClosestResolvedTarget(Vec3 origin, float partialTick) {
        if (origin == null || mc.level == null || mc.player == null) {
            return null;
        }

        LivingEntity bestTarget = null;
        double bestDistSq = Double.MAX_VALUE;

        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (entity == mc.player) continue;
            if (!isTargetAllowed(living)) continue;

            living = resolveTarget(living);
            if (living == null) continue;

            double distSq = origin.distanceToSqr(living.getPosition(partialTick));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestTarget = living;
            }
        }

        return bestTarget;
    }
}
