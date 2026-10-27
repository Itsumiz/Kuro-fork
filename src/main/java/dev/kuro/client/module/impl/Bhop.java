package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;

/** Bunny hop: jumps automatically every time you land while moving. Optional air speed (0 = vanilla). */
public class Bhop extends Module {
    private final Setting.Num airSpeed = add(new Setting.Num("Air speed", 0.0, 0.0, 0.6, 0.02));
    private boolean pressedByUs;

    public Bhop() { super("Bhop", Category.MOVEMENT); }

    @Override protected void onDisable() { release(); }

    private void release() {
        if (pressedByUs) { pressedByUs = false; KeyMapping.setAll(); } // re-sync with the real key
    }

    private static double[] moveDir(Options o, double yawDeg) {
        double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double s = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        double yaw = Math.toRadians(yawDeg);
        double dx = -Math.sin(yaw) * f + Math.cos(yaw) * s;
        double dz = Math.cos(yaw) * f + Math.sin(yaw) * s;
        double len = Math.hypot(dx, dz);
        return len < 1e-6 ? null : new double[]{dx / len, dz / len};
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.screen != null || Freecam.active()) { release(); return; }
        Options o = mc.options;
        boolean moving = moveDir(o, mc.player.getYRot()) != null;
        boolean ok = moving && mc.player.onGround() && !mc.player.isShiftKeyDown()
                && !mc.player.isInWater() && !mc.player.isInLava() && !mc.player.onClimbable();
        if (ok) { o.keyJump.setDown(true); pressedByUs = true; }
        else release();
    }

    @Override
    public void onTick() {
        if (airSpeed.value <= 0 || mc.player == null || mc.screen != null || Freecam.active()) return;
        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()) return;
        double[] d = moveDir(mc.options, mc.player.getYRot());
        if (d == null) return;
        var v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(d[0] * airSpeed.value, v.y, d[1] * airSpeed.value);
    }
}
