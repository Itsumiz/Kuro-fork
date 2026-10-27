package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

/**
 * Vape-style KeepSprint for Fabric 1.21.x.
 *
 * After an attack, vanilla multiplies horizontal velocity by 0.6 and may stop sprint.
 * This restores a configurable fraction of pre-hit motion and optionally keeps sprinting.
 *
 * 0.6 = vanilla slowdown, 1.0 = no slowdown (full keep momentum).
 */
public class KeepSprint extends Module {
    private static KeepSprint instance;

    private final Setting.Num slowDownNormal = add(new Setting.Num("Slow down normal", 1.0, 0.6, 1.0, 0.01));
    private final Setting.Num slowDownVelocity = add(new Setting.Num("Slow down on velocity", 1.0, 0.6, 1.0, 0.01));
    private final Setting.Bool keepSprintNormal = add(new Setting.Bool("Keep sprint normal", true));
    private final Setting.Bool keepSprintVelocity = add(new Setting.Bool("Keep sprint on velocity", true));
    private final Setting.Bool onlyInAir = add(new Setting.Bool("Only in air", false));

    private double preX, preZ;
    private boolean captured;
    private boolean wasSprinting;

    public KeepSprint() {
        super("Keep Sprint", Category.MOVEMENT);
        instance = this;
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClientSide() || player != mc.player || !isEnabled()) return InteractionResult.PASS;
            capture(player);
            // apply after vanilla attack runs — schedule via post-tick flag
            pending = true;
            pendingEntity = entity;
            return InteractionResult.PASS;
        });
    }

    private boolean pending;
    private Entity pendingEntity;

    private void capture(Player p) {
        Vec3 v = p.getDeltaMovement();
        preX = v.x;
        preZ = v.z;
        wasSprinting = p.isSprinting();
        captured = true;
    }

    @Override
    public void onTick() {
        if (!pending || mc.player == null) return;
        pending = false;
        if (!captured) return;
        captured = false;

        if (onlyInAir.value && mc.player.onGround()) return;

        boolean takingKb = mc.player.hurtTime > 0;
        double factor = takingKb ? slowDownVelocity.value : slowDownNormal.value;
        boolean sprint = takingKb ? keepSprintVelocity.value : keepSprintNormal.value;

        // Vanilla already applied 0.6. We want final = pre * factor.
        // Current ≈ pre * 0.6, so scale by (factor / 0.6).
        Vec3 v = mc.player.getDeltaMovement();
        double scale = factor / 0.6;
        // Prefer restoring from captured pre-hit motion for stability
        double nx = preX * factor;
        double nz = preZ * factor;
        // If velocity was almost zero, fall back to scaling current
        if (Math.abs(preX) < 1e-6 && Math.abs(preZ) < 1e-6) {
            nx = v.x * scale;
            nz = v.z * scale;
        }
        mc.player.setDeltaMovement(nx, v.y, nz);

        if (sprint && wasSprinting) {
            mc.player.setSprinting(true);
        }
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
