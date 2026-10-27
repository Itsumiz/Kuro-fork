package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.phys.Vec3;

/**
 * Reduces knockback. Between the end of one tick and the start of the next only the network can change your
 * velocity, so any jump in it is knockback (or an explosion); this scales that jump by the chosen percentages.
 */
public class Velocity extends Module {
    private final Setting.Num horizontal = add(new Setting.Num("Horizontal %", 0, 0, 100, 5));
    private final Setting.Num vertical = add(new Setting.Num("Vertical %", 0, 0, 100, 5));
    private Vec3 lastEnd = Vec3.ZERO;

    public Velocity() { super("Velocity", Category.COMBAT); }

    @Override public boolean early() { return true; }          // compare before other modules touch the velocity

    @Override protected void onEnable() { lastEnd = mc.player != null ? mc.player.getDeltaMovement() : Vec3.ZERO; }

    @Override
    public void onPreTick() {
        if (mc.player == null) return;
        Vec3 v = mc.player.getDeltaMovement();
        Vec3 d = v.subtract(lastEnd);
        if (Math.hypot(d.x, d.z) < 0.08 && Math.abs(d.y) < 0.08) return;     // no knockback
        double h = horizontal.value / 100.0, vert = vertical.value / 100.0;
        mc.player.setDeltaMovement(lastEnd.x + d.x * h, lastEnd.y + d.y * vert, lastEnd.z + d.z * h);
    }

    @Override public void onPostTick() { if (mc.player != null) lastEnd = mc.player.getDeltaMovement(); }
}
