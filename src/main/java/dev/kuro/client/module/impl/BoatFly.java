package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Boat Fly: flies the boat you are sitting in, and every few ticks gets out and back in again.
 *
 * While you ride, the boat's motion is set from your keys: WASD for the heading, jump to climb, otherwise it hovers
 * (or sinks slowly). Every "Cycle" ticks the module taps sneak for one tick (the normal way out of a boat) and the tick after
 * that right-clicks the same boat to get back in, so the server never sees one long flight in a single ride.
 *
 * Notes:
 *  - Whether a server lets this through depends on the server. Many plugins flag vehicle flight; use it where it is allowed.
 *  - The module switches itself off if the boat cannot be entered again (too far away, taken, broken).
 *  - The re-enter click is the one version-sensitive call (remount()); if the game version changes it is the only place to adjust.
 */
public class BoatFly extends Module {
    private final Setting.Num hSpeed = add(new Setting.Num("Horizontal speed", 0.8, 0.1, 3.0, 0.1));
    private final Setting.Num climb = add(new Setting.Num("Climb speed (jump)", 0.35, 0.05, 1.5, 0.05));
    private final Setting.Num sink = add(new Setting.Num("Sink speed (no jump)", 0.0, 0.0, 0.5, 0.01));
    private final Setting.Num cycle = add(new Setting.Num("Exit / re-enter every (ticks)", 8, 2, 40, 1));
    private final Setting.Bool needKeys = add(new Setting.Bool("Only move while a key is held", false));

    private enum Phase { RIDING, EXIT, ENTER }
    private Phase phase = Phase.RIDING;
    private Entity boat;
    private int ticks, enterTries;
    private boolean sneakByUs;

    public BoatFly() { super("Boat Fly", Category.MOVEMENT); }

    @Override
    protected void onEnable() { reset(); }

    @Override
    protected void onDisable() { releaseSneak(); reset(); }

    private void reset() { phase = Phase.RIDING; boat = null; ticks = 0; enterTries = 0; }

    private static boolean isBoat(Entity e) {
        if (e == null) return false;
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        return path.endsWith("boat") || path.endsWith("raft");
    }

    private void releaseSneak() {
        if (sneakByUs) { sneakByUs = false; KeyMapping.setAll(); }       // hand the key back to the real keyboard
    }

    /** Start of the tick: the key presses for getting out go here, before the player ticks. */
    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        switch (phase) {
            case RIDING -> {
                Entity v = mc.player.getVehicle();
                if (isBoat(v)) { boat = v; ticks++; }
                else if (boat != null && ticks > 0) { phase = Phase.ENTER; enterTries = 0; }     // thrown out by something else
                if (isBoat(v) && ticks >= cycle.intValue()) {
                    phase = Phase.EXIT; ticks = 0;
                    mc.options.keyShift.setDown(true);                                           // sneak = leave the vehicle
                    sneakByUs = true;
                }
            }
            case EXIT -> {
                releaseSneak();
                phase = mc.player.getVehicle() == null ? Phase.ENTER : Phase.RIDING;
                enterTries = 0;
                if (phase == Phase.ENTER) remount();                                              // same tick the sneak was let go
            }
            case ENTER -> {
                if (mc.player.getVehicle() != null) { phase = Phase.RIDING; ticks = 0; return; }
                if (++enterTries > 10 || boat == null || !boat.isAlive() || mc.player.distanceTo(boat) > 5.0) {
                    CommandManager.say("Boat Fly: could not get back into the boat, switching off.");
                    setEnabled(false);
                    return;
                }
                remount();
            }
        }
    }

    /** Right-click the boat to sit in it again. The one call that depends on the game version. */
    private void remount() {
        if (boat == null || mc.gameMode == null || mc.player == null) return;
        mc.gameMode.interact(mc.player, boat, InteractionHand.MAIN_HAND);
    }

    /** The boat's motion while you sit in it. */
    @Override
    public void onTick() {
        if (mc.player == null || mc.screen != null) return;
        Entity v = mc.player.getVehicle();
        if (!isBoat(v)) return;
        Options o = mc.options;

        double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double s = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        boolean up = o.keyJump.isDown();
        if (needKeys.value && f == 0 && s == 0 && !up) return;

        double mx = 0, mz = 0;
        if (f != 0 || s != 0) {
            double yaw = Math.toRadians(mc.player.getYRot());
            double dx = -Math.sin(yaw) * f + Math.cos(yaw) * s;
            double dz = Math.cos(yaw) * f + Math.sin(yaw) * s;
            double len = Math.hypot(dx, dz);
            mx = dx / len * hSpeed.value;
            mz = dz / len * hSpeed.value;
        }
        double my = up ? climb.value : -sink.value;
        v.setDeltaMovement(new Vec3(mx, my, mz));
        v.fallDistance = 0;
    }
}
