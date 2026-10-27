package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Takes over your elytra while you are gliding.
 * Control mode: W = fly where you look, S = back, A/D = sideways, Space = up, Shift = down, no input = hover.
 * Boost mode: hold W to accelerate along your view up to the speed limit (like fireworks that never run out).
 * Start flying the normal way (jump, then jump again in the air).
 */
public class ElytraFly extends Module {
    private final Setting.Num speed = add(new Setting.Num("Speed", 1.5, 0.5, 4.0, 0.1));
    private final Setting.Num vertical = add(new Setting.Num("Vertical speed", 0.8, 0.2, 2.0, 0.1));
    private final Setting.Bool boost = add(new Setting.Bool("Boost mode", false));

    public ElytraFly() { super("Elytra Fly", Category.MOVEMENT); }

    @Override
    public void onPreTick() {
        LocalPlayer p = mc.player;
        if (p == null || mc.screen != null || !p.isFallFlying()) return;

        var o = mc.options;
        boolean fwd = o.keyUp.isDown(), back = o.keyDown.isDown();
        boolean left = o.keyLeft.isDown(), right = o.keyRight.isDown();
        boolean up = o.keyJump.isDown(), down = o.keyShift.isDown();
        Vec3 look = p.getLookAngle();
        double sp = speed.value;

        if (boost.value) {
            if (!fwd) return;
            Vec3 v = p.getDeltaMovement().add(look.scale(0.1));
            double len = v.length();
            if (len > sp) v = v.scale(sp / len);
            p.setDeltaMovement(v);
            return;
        }

        double yaw = Math.toRadians(p.getYRot());
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double f = (fwd ? 1 : 0) - (back ? 1 : 0), s = (right ? 1 : 0) - (left ? 1 : 0);

        double vx = look.x * f * sp + rx * s * sp;
        double vy = look.y * f * sp;
        double vz = look.z * f * sp + rz * s * sp;
        if (up) vy += vertical.value;
        if (down) vy -= vertical.value;
        if (f == 0 && s == 0 && !up && !down) vy = 0.02;      // hover (cancels the small sink the game adds each tick)
        p.setDeltaMovement(vx, vy, vz);
    }
}
