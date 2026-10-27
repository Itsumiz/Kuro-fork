package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import net.minecraft.client.Options;
import net.minecraft.world.entity.player.Player;

/**
 * Removes the walking slowdown while eating / drinking / blocking. Done without touching game code: while
 * you use an item the game scales your movement input by 0.2, so just before each tick this adds back the
 * missing speed so you end up moving at normal walking speed.
 */
public class NoSlow extends Module {
    public NoSlow() { super("No Slow", Category.MOVEMENT); }

    @Override
    public void onPreTick() {
        Player p = mc.player;
        if (p == null || mc.screen != null || Freecam.active()) return;
        if (!p.isUsingItem() || p.isPassenger() || !p.onGround() || p.isShiftKeyDown() || p.isInWater() || p.onClimbable()) return;

        Options o = mc.options;
        double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double s = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        double yaw = Math.toRadians(p.getYRot());
        double dx = -Math.sin(yaw) * f + Math.cos(yaw) * s;
        double dz = Math.cos(yaw) * f + Math.sin(yaw) * s;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return;
        dx /= len; dz /= len;

        // normal ground walking covers ~2.158 x movementSpeed per tick; the slowed input still supplies 0.2 x of it
        double ms = p.getSpeed();
        double v = ms * (2.158 - 0.2);
        p.setDeltaMovement(dx * v, p.getDeltaMovement().y, dz * v);
    }
}
