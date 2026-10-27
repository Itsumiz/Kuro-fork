package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.phys.Vec3;

/**
 * Freeze. Default ("Move locally" on): the server is told you are standing still at the spot where you switched it
 * on - no movement packets leave your client - while you keep walking, flying and looking around on your own screen.
 * Switch "Move locally" off and it instead locks you in place locally (no walking or falling, knockback cancelled).
 * Servers usually pull you back (rubber-band) if you stay away from the held spot and then release, so "Snap back
 * on release" can put you back there for you.
 */
public class Freeze extends Module {
    private static Freeze instance;

    private final Setting.Bool local = add(new Setting.Bool("Move locally", true));               // on = hold the server position only
    private final Setting.Bool snapBack = add(new Setting.Bool("Snap back on release", false));
    private final Setting.Bool lockPosition = add(new Setting.Bool("Local lock: snap if pushed", true));
    private final Setting.Bool midAir = add(new Setting.Bool("Local lock: freeze mid-air", true));
    private Vec3 anchor;
    private int letThrough;     // ticks after a server teleport during which movement packets are allowed (so it can be confirmed)

    public Freeze() {
        super("Freeze", Category.MOVEMENT);
        instance = this;
    }

    /** True while outgoing movement packets must be swallowed (checked by the network mixin). */
    public static boolean holding() { return instance != null && instance.isEnabled() && instance.local.value && instance.letThrough <= 0; }

    /** Called when the server moves you (teleport / rubber-band): hold from the new spot and let the confirmation through. */
    public static void onServerTeleport() {
        if (instance == null || !instance.isEnabled() || mc.player == null) return;
        instance.letThrough = 3;
        instance.anchor = null;      // re-read the position next tick
    }

    @Override
    protected void onEnable() {
        letThrough = 0;
        anchor = mc.player != null ? new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ()) : null;
    }

    @Override
    protected void onDisable() {
        if (mc.player != null && anchor != null && local.value && snapBack.value) {
            mc.player.setPos(anchor.x, anchor.y, anchor.z);
            mc.player.setDeltaMovement(0, 0, 0);
        }
        anchor = null;
    }

    /** Runs before the player ticks, so the frozen state is what the game simulates this tick. */
    @Override public boolean early() { return true; }

    @Override public void onPreTick() { tickHold(); }
    @Override public void onTick() { if (!local.value) hold(); }

    private void tickHold() {
        if (mc.player == null) return;
        if (letThrough > 0) letThrough--;
        if (anchor == null) anchor = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (!local.value) hold();
    }

    /** Local lock mode: no movement at all on your own screen. */
    private void hold() {
        if (mc.player == null || anchor == null) return;
        Vec3 v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(0, midAir.value ? 0 : Math.min(0, v.y), 0);
        if (lockPosition.value) {
            double dx = mc.player.getX() - anchor.x, dz = mc.player.getZ() - anchor.z, dy = mc.player.getY() - anchor.y;
            boolean fallingFree = !midAir.value;
            if (dx * dx + dz * dz > 1.0E-4 || (!fallingFree && dy * dy > 1.0E-4)) {
                mc.player.setPos(anchor.x, fallingFree ? mc.player.getY() : anchor.y, anchor.z);
            }
            if (fallingFree) anchor = new Vec3(anchor.x, mc.player.getY(), anchor.z);
        }
    }
}
