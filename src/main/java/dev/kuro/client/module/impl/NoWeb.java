package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Moves you through cobwebs by adding speed on top of the web slowdown (Grim-style: jump = up, sneak = down). */
public class NoWeb extends Module {
    private final Setting.Num speed = add(new Setting.Num("Speed", 0.35, 0.35, 0.65, 0.05));

    public NoWeb() { super("No Web", Category.MOVEMENT); }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (!inWeb()) return;

        double fwd = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double side = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double x = 0, z = 0;
        if (fwd != 0 || side != 0) {
            double yaw = Math.toRadians(mc.player.getYRot());
            double len = Math.sqrt(fwd * fwd + side * side);
            fwd /= len; side /= len;
            x = (-Math.sin(yaw) * fwd + Math.cos(yaw) * side) * speed.value;
            z = (Math.cos(yaw) * fwd + Math.sin(yaw) * side) * speed.value;
        }
        double y = mc.options.keyJump.isDown() ? 0.65 : mc.options.keyShift.isDown() ? -0.65 : 0.0;
        Vec3 v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(v.x + x, y, v.z + z);
    }

    private boolean inWeb() {
        AABB box = mc.player.getBoundingBox();
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (mc.level.getBlockState(p).is(Blocks.COBWEB)) return true;
        }
        return false;
    }
}
