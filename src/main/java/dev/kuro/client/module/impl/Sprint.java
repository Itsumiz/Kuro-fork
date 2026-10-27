package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.effect.MobEffects;

/**
 * Vape-style Sprint for Fabric 1.21.x.
 * Holds sprint when you are allowed to sprint.
 */
public class Sprint extends Module {
    private final Setting.Bool cancelInvis = add(new Setting.Bool("Cancel invis", false));
    private final Setting.Bool omni = add(new Setting.Bool("Omni (any direction)", false));

    public Sprint() {
        super("Sprint", Category.MOVEMENT);
    }

    @Override
    protected void onDisable() {
        if (mc.options != null) mc.options.keySprint.setDown(false);
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.options == null) return;
        if (mc.screen != null) return;
        if (Freecam.active() || mc.player.isSpectator()) return;
        if (cancelInvis.value && mc.player.hasEffect(MobEffects.INVISIBILITY)) return;

        boolean forward = mc.options.keyUp.isDown();
        boolean strafe = mc.options.keyLeft.isDown() || mc.options.keyRight.isDown()
                || mc.options.keyDown.isDown();
        boolean moving = omni.value ? (forward || strafe) : forward;

        boolean can = moving
                && !mc.player.isShiftKeyDown()
                && !mc.player.isFallFlying()
                && mc.player.getFoodData().getFoodLevel() > 6
                && !mc.player.isInWater();

        if (can) {
            KeyMapping sprint = mc.options.keySprint;
            if (!sprint.isDown()) sprint.setDown(true);
            if (!mc.player.isSprinting()) mc.player.setSprinting(true);
        }
    }
}
