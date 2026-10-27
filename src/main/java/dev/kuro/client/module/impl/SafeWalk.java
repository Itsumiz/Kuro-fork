package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Stops you walking off an edge when the drop would hurt. Where the ground below is within "Max drop"
 * blocks (or there is water) you walk normally; where it is further, you are edge-clipped exactly as if
 * you were sneaking (see PlayerEntityMixin). The default of 3 matches vanilla: a 4+ block fall damages you.
 */
public class SafeWalk extends Module {
    private static SafeWalk instance;
    private final Setting.Num maxDrop = add(new Setting.Num("Max drop", 3, 1, 10, 1));
    private boolean danger;

    public SafeWalk() { super("Safe Walk", Category.MOVEMENT); instance = this; }

    /** True while the player should be edge-clipped this tick. */
    public static boolean clip() { return instance != null && instance.isEnabled() && instance.danger; }

    @Override protected void onDisable() { danger = false; }

    @Override
    public void onPreTick() {
        danger = false;
        if (mc.player == null || mc.level == null || mc.screen != null || Freecam.active()) return;
        if (!mc.player.onGround() || mc.player.getAbilities().flying || mc.player.isInWater()) return;

        // directions we are about to move in: held keys and current momentum
        Options o = mc.options;
        double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double s = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        double yaw = Math.toRadians(mc.player.getYRot());
        double ix = -Math.sin(yaw) * f + Math.cos(yaw) * s;
        double iz = Math.cos(yaw) * f + Math.sin(yaw) * s;
        Vec3 vel = mc.player.getDeltaMovement();

        if (check(ix, iz) || check(vel.x, vel.z)) danger = true;
    }

    /** Samples the ground just ahead (and slightly to each side) in direction (dx, dz). */
    private boolean check(double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.02) return false;
        dx /= len; dz /= len;
        double px = mc.player.getX(), pz = mc.player.getZ();
        int feetY = Mth.floor(mc.player.getY() + 0.001);
        for (double dist : new double[]{0.35, 0.8}) {
            for (double side : new double[]{-0.3, 0.0, 0.3}) {
                double x = px + dx * dist - dz * side;
                double z = pz + dz * dist + dx * side;
                if (drops(x, z, feetY)) return true;
            }
        }
        return false;
    }

    /** True if there is nothing solid (and no water) within maxDrop blocks below the feet at (x, z). */
    private boolean drops(double x, double z, int feetY) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        for (int j = 0; j <= maxDrop.intValue(); j++) {
            BlockPos pos = new BlockPos(bx, feetY - 1 - j, bz);
            var st = mc.level.getBlockState(pos);
            if (!st.getCollisionShape(mc.level, pos).isEmpty() || !st.getFluidState().isEmpty()) return false;
        }
        return true;
    }
}
