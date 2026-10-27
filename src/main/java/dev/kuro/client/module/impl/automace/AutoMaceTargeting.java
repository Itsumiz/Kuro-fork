package dev.kuro.client.module.impl.automace;

import dev.kuro.client.module.impl.AutoMace;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.RotationHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AutoMaceTargeting {

    private static final Minecraft mc = Minecraft.getInstance();

    private final AutoMace parent;

    public enum Priority { DISTANCE, LOWEST_HP, HIGHEST_HP }
    public enum AimPoint { CENTER, NEAREST, CLOSEST }

    public AutoMaceTargeting(AutoMace parent) {
        this.parent = parent;
    }

    public float[] applyGCDMouseFix(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
        float sens = mc.options.sensitivity().get().floatValue() * 0.6F + 0.2F;
        float gcd = sens * sens * sens * 1.2F;
        float finalYaw = currentYaw + Math.round((targetYaw - currentYaw) / gcd) * gcd;
        float finalPitch = currentPitch + Math.round((targetPitch - currentPitch) / gcd) * gcd;
        finalPitch = Math.max(-90.0F, Math.min(90.0F, finalPitch));
        return new float[]{finalYaw, finalPitch};
    }

    public float[] calculateAdvancedRotations(Player player, Entity entity) {
        AABB boundingBox = entity.getBoundingBox();
        Vec3 eyes = player.getEyePosition();
        Vec3 targetPoint = null;

        float currentYaw = RotationHandler.isSilentActive() ? RotationHandler.getSilentYaw() : player.getYRot();
        float currentPitch = RotationHandler.isSilentActive() ? RotationHandler.getSilentPitch() : player.getXRot();

        float f = Mth.cos(-currentYaw * 0.017453292F - (float) Math.PI);
        float f1 = Mth.sin(-currentYaw * 0.017453292F - (float) Math.PI);
        float f2 = -Mth.cos(-currentPitch * 0.017453292F);
        float f3 = Mth.sin(-currentPitch * 0.017453292F);

        Vec3 lookVec = new Vec3(f1 * f2, f3, f * f2);

        double maxT = parent.getAimRange().value;
        double step = maxT / 15.0;
        double bestDistSq = Double.MAX_VALUE;

        for (int i = 0; i <= 15; i++) {
            double t = i * step;
            Vec3 rayPoint = eyes.add(lookVec.scale(t));

            double clampedX = Math.max(boundingBox.minX, Math.min(rayPoint.x, boundingBox.maxX));
            double clampedY = Math.max(boundingBox.minY, Math.min(rayPoint.y, boundingBox.maxY));
            double clampedZ = Math.max(boundingBox.minZ, Math.min(rayPoint.z, boundingBox.maxZ));

            Vec3 boxPoint = new Vec3(clampedX, clampedY, clampedZ);

            double dSq = rayPoint.distanceToSqr(boxPoint);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                targetPoint = boxPoint;
            }
        }

        if (targetPoint == null || !player.hasLineOfSight(entity)) {
            targetPoint = new Vec3(entity.getX(), entity.getY() + (entity.getBbHeight() * 0.75D), entity.getZ());
        }

        double diffX = targetPoint.x - eyes.x;
        double diffY = targetPoint.y - eyes.y;
        double diffZ = targetPoint.z - eyes.z;
        double hypotenuse = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float) -(Math.atan2(diffY, hypotenuse) * 180.0D / Math.PI);

        return new float[]{yaw, pitch};
    }

    /** A valid target inside the attack reach. */
    public boolean isValidTarget(Entity entity) { return isValidTarget(entity, parent.reach()); }

    /** A valid target whose hitbox is within maxDist of the eyes (the same measure the server uses for reach). */
    public boolean isValidTarget(Entity entity, double maxDist) {
        if (entity == null || entity == mc.player || entity == mc.getCameraEntity()) return false;
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return false;
        if (entity.getBoundingBox().distanceToSqr(mc.player.getEyePosition()) > maxDist * maxDist) return false;

        if (entity instanceof Player p) {
            if (!parent.getTargetPlayers().value) return false;
            if (p.isCreative() || p.isSpectator()) return false;
            if (Friends.shield(p)) return false;
            return true;
        } else {
            return parent.getTargetMobs().value;
        }
    }

    /** Nearest valid target in sight, looking out to the Aim Range (the attack itself is limited to the reach separately). */
    public Entity findOptimalTarget() { return findOptimalTarget(Math.max(parent.getAimRange().value, parent.reach())); }

    public Entity findOptimalTarget(double maxDist) {
        double bestMetric = Double.MAX_VALUE;
        Entity best = null;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!isValidTarget(e, maxDist)) continue;
            if (!mc.player.hasLineOfSight(e)) continue;

            double metricValue = mc.player.distanceToSqr(e);

            if (metricValue < bestMetric) {
                bestMetric = metricValue;
                best = e;
            }
        }
        return best;
    }

    public static Entity find(double range, boolean targetPlayers, boolean targetMobs, Priority priority, double fov) {
        if (mc.player == null || mc.level == null) return null;
        Entity best = null;
        double bestMetric = Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || !(e instanceof LivingEntity living) || !living.isAlive()) continue;

            double dist = mc.player.distanceTo(e);
            if (dist > range) continue;

            if (e instanceof Player p) {
                if (!targetPlayers || p.isSpectator() || p.isCreative() || Friends.shield(p)) continue;
            } else {
                if (!targetMobs) continue;
            }

            if (!mc.player.hasLineOfSight(e)) continue;

            if (fov < 360.0) {   // "Rocket snap FOV" used to be ignored
                Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
                if (to.lengthSqr() > 1.0E-6) {
                    double ang = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, look.dot(to.normalize())))));
                    if (ang > fov / 2.0) continue;
                }
            }

            double metric = switch (priority) {
                case LOWEST_HP -> living.getHealth();
                case HIGHEST_HP -> -living.getHealth();
                default -> dist;
            };

            if (metric < bestMetric) {
                bestMetric = metric;
                best = e;
            }
        }
        return best;
    }

    public static float[] angles(Vec3 target) {
        Vec3 eyes = mc.player.getEyePosition();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;
        double hyp = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, hyp));
        return new float[]{yaw, pitch};
    }

    public static Vec3 aimPoint(Entity entity, AimPoint point) {
        return new Vec3(entity.getX(), entity.getY() + (entity.getBbHeight() * 0.75), entity.getZ());
    }
}