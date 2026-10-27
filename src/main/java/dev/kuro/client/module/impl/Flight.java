package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.Options;

/** Free flight: move with the normal movement keys, Jump = up, Sneak = down. */
public class Flight extends Module {
    private final Setting.Num speed = add(new Setting.Num("Speed", 0.5, 0.1, 3.0, 0.1));

    public Flight() { super("Flight", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mc.player == null || mc.screen != null || Freecam.active()) return;
        Options o = mc.options;

        double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double s = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        double u = (o.keyJump.isDown() ? 1 : 0) - (o.keyShift.isDown() ? 1 : 0);

        double yaw = Math.toRadians(mc.player.getYRot());
        double dx = -Math.sin(yaw) * f + Math.cos(yaw) * s;
        double dz = Math.cos(yaw) * f + Math.sin(yaw) * s;
        double len = Math.hypot(dx, dz);
        if (len > 1e-6) { dx /= len; dz /= len; }

        mc.player.setDeltaMovement(dx * speed.value, u * speed.value, dz * speed.value);
    }
}
