package dev.kuro.client.module.impl;

import dev.kuro.client.event.EventHandler;
import dev.kuro.client.event.events.MotionEvent;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class Scaffold extends Module {
    private final Setting.Bool legitMode = add(new Setting.Bool("Legit Mode", true));
    private final Setting.Num sneakDelay = add(new Setting.Num("Sneak Delay (ms)", 100, 0, 500, 10));

    private long lastSneakTime = 0;
    private boolean sneaking = false;

    public Scaffold() { super("Scaffold", Category.MOVEMENT); }

    @Override
    protected void onDisable() {
        MovementHelper.releaseAllKeys();
    }

    @EventHandler
    public void onMotion(MotionEvent event) {
        if (event.getState() != MotionEvent.State.PRE || mc.player == null || mc.level == null || mc.screen != null) return;
        if (mc.player.isFallFlying() || mc.player.isSpectator()) return;

        // 1. Ensure we are holding a block
        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem)) {
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).getItem() instanceof BlockItem) {
                    BlockPlacer.selectForce(i);
                    break;
                }
            }
        }

        // 2. Sneak Logic
        if (legitMode.value) {
            Vec3 motion = mc.player.getDeltaMovement();
            double nextX = mc.player.getX() + motion.x;
            double nextZ = mc.player.getZ() + motion.z;
            
            boolean hasBlockBelow = MovementHelper.hasBlockBelow(mc.player, nextX, nextZ);
            if (!hasBlockBelow && mc.player.onGround()) {
                if (!sneaking) {
                    MovementHelper.setKey(mc.options.keyShift, true);
                    sneaking = true;
                    lastSneakTime = System.currentTimeMillis();
                }
            } else {
                if (sneaking && System.currentTimeMillis() - lastSneakTime > sneakDelay.value) {
                    MovementHelper.setKey(mc.options.keyShift, false);
                    sneaking = false;
                }
            }
        }

        // 3. Place block
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(4.5));

        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos placePos = hit.getBlockPos().relative(hit.getDirection());
            
            if (placePos.equals(mc.player.blockPosition()) || placePos.equals(mc.player.blockPosition().above())) {
                return;
            }

            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }
}