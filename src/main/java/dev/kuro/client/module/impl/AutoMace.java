package dev.kuro.client.module.impl;

import dev.kuro.client.event.EventHandler;
import dev.kuro.client.event.events.MotionEvent;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.module.impl.automace.AutoMaceTargeting;
import dev.kuro.client.module.impl.automace.RocketDiveHandler;
import dev.kuro.client.module.impl.automace.ShieldBreakHandler;
import dev.kuro.client.module.impl.automace.SpearDtapHandler;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.RotationEngine;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public class AutoMace extends Module {

    public enum ShieldMode { NONE, STUN_SLAM, DRAIN }

    // ---- Settings ----
    private final Setting.Num range = add(new Setting.Num("Range", 3.0, 1.0, 3.0, 0.1));
    private final Setting.Num aimRange = add(new Setting.Num("Aim Range", 4.5, 1.0, 8.0, 0.1));
    private final Setting.Num minFallDistance = add(new Setting.Num("Min Fall Distance", 1.5, 1.5, 8.0, 0.1));
    private final Setting.Num densityThreshold = add(new Setting.Num("Density Threshold", 3.0, 1.5, 12.0, 0.1));
    private final Setting.Bool targetPlayers = add(new Setting.Bool("Target Players", true));
    private final Setting.Bool targetMobs = add(new Setting.Bool("Target Mobs", false));
    private final Setting.Bool autoSwitch = add(new Setting.Bool("Auto Switch", true));
    private final Setting.Num shieldMode = add(new Setting.Num("Shield Mode (0=None, 1=Slam, 2=Drain)", 1, 0, 2, 1));
    private final Setting.Bool checkFalling = add(new Setting.Bool("Check Falling", true));
    private final Setting.Bool fastStunSlam = add(new Setting.Bool("Fast Stun Slam", true));
    private final Setting.Num stunSlamDelay = add(new Setting.Num("Stun Slam Delay (ms)", 50, 0, 500, 5));
    private final Setting.Bool stayOnMace = add(new Setting.Bool("Stay on Mace", false));
    private final Setting.Bool swapBackOnHit = add(new Setting.Bool("Swap Back on Hit", true));
    private final Setting.Bool retrySlam = add(new Setting.Bool("Retry Slam", true));
    private final Setting.Num retryDelay = add(new Setting.Num("Retry Delay", 250, 0, 1000, 25));
    private final Setting.Bool spearDtap = add(new Setting.Bool("Spear Mace Dtap", false));
    // Swapping weapons resets the vanilla attack cooldown, so with this on a hit right after a swap has to wait out the
    // whole cooldown (a mace is ~1.7 s): too slow for a fall. Off = swing as soon as the crosshair is on the target.
    private final Setting.Bool requireCharge = add(new Setting.Bool("Require Full Charge", false));

    // ---- Rocket Dive Settings ----
    private final Setting.Bool autoRocket = add(new Setting.Bool("Auto rocket dive", false));
    private final Setting.Bool rocketLoop = add(new Setting.Bool("Rocket loop", false));
    private final Setting.Num rocketTargetRange = add(new Setting.Num("Rocket target range", 48, 8, 128, 1));
    private final Setting.Num rocketAttackRange = add(new Setting.Num("Rocket attack range", 3.0, 2.0, 3.0, 0.1));
    private final Setting.Num rocketSnapFov = add(new Setting.Num("Rocket snap FOV", 360, 10, 360, 1));
    private final Setting.Num rocketHeightAbove = add(new Setting.Num("Rocket height above", 8, 0, 30, 0.5));
    private final Setting.Num rocketFireworkDelay = add(new Setting.Num("Firework delay ms", 650, 100, 2500, 25));
    private final Setting.Num rocketDropDelay = add(new Setting.Num("Drop delay ms", 120, 0, 1000, 10));
    private final Setting.Num rocketAttackDelay = add(new Setting.Num("Rocket attack delay ms", 100, 0, 500, 10));
    private final Setting.Num rocketReequipDelay = add(new Setting.Num("Re-equip delay ms", 150, 0, 1000, 10));
    private final Setting.Num rocketGlideDelay = add(new Setting.Num("Glide delay ms", 100, 0, 1000, 10));
    private final Setting.Num rocketAimOffset = add(new Setting.Num("Rocket aim offset", 1.5, 0, 4, 0.1));
    private final Setting.Bool rocketRecover = add(new Setting.Bool("Rocket Recover", true));

    // ---- State ----
    private int savedSlot = -1;
    private boolean maceHit = false;
    private long lastMaceHitTime = 0L;
    private long swapBackAt = 0L;
    private static boolean autoAttacking = false;
    private int lastAttackTick = Integer.MIN_VALUE;   // vanilla: one attack per tick
    private boolean prevRocket, prevSpear, prevPlayers = true, prevMobs;

    private double fallStartY = -1;
    private boolean isFalling = false;

    // ---- Handlers ----
    private final AutoMaceTargeting targeting = new AutoMaceTargeting(this);
    private final ShieldBreakHandler shieldBreakHandler = new ShieldBreakHandler(this);
    private final RocketDiveHandler rocketDive = new RocketDiveHandler(this);
    private final SpearDtapHandler spearDtapHandler = new SpearDtapHandler(this, shieldBreakHandler, targeting);

    public AutoMace() {
        super("Auto Mace", Category.COMBAT);
    }

    @Override
    protected void onEnable() {
        resetFall();
        rocketDive.reset(true);
        savedSlot = -1;
        lastMaceHitTime = 0L;
        swapBackAt = 0L;
        lastAttackTick = Integer.MIN_VALUE;
        prevRocket = autoRocket.value;
        prevSpear = spearDtap.value;
        prevPlayers = targetPlayers.value;
        prevMobs = targetMobs.value;
        spearDtapHandler.reset();
        shieldBreakHandler.reset();
        RotationEngine.INSTANCE.release();
    }

    @Override
    protected void onDisable() {
        if (!stayOnMace.value && savedSlot != -1 && mc.player != null) {
            BlockPlacer.selectForce(savedSlot);   // clean-up only
        }
        spearDtapHandler.onDisable();
        rocketDive.reset(true);
        resetFall();
        shieldBreakHandler.reset();
        lastMaceHitTime = 0L;
        swapBackAt = 0L;
        RotationEngine.INSTANCE.release();
    }

    /**
     * Keeps the settings from ending up in a combination that cannot work. Runs every tick, so it also fixes values
     * that came from a loaded config. Where two settings fight, the one that was switched on last wins.
     */
    private void enforceSettings() {
        if (aimRange.value < range.value) aimRange.value = range.value;            // you cannot hit from further than you aim
        if (stayOnMace.value && swapBackOnHit.value) swapBackOnHit.value = false;   // "swap back" can never happen while staying on the mace
        if (!rocketRecover.value && rocketLoop.value) rocketLoop.value = false;     // looping needs the elytra back after the hit
        if (!autoRocket.value && rocketLoop.value) rocketLoop.value = false;

        if (autoRocket.value && spearDtap.value) {                                  // gliding and charging a spear are exclusive
            if (prevRocket && !prevSpear) autoRocket.value = false; else spearDtap.value = false;
        }
        if (!targetPlayers.value && !targetMobs.value) {                            // nothing left that could be a target
            if (prevPlayers) targetPlayers.value = true;
            else if (prevMobs) targetMobs.value = true;
            else targetPlayers.value = true;
        }
        prevRocket = autoRocket.value;
        prevSpear = spearDtap.value;
        prevPlayers = targetPlayers.value;
        prevMobs = targetMobs.value;
    }

    // ---- reach ----

    /** The furthest this module will ever hit from: the Range setting, but never more than the game lets the player reach. */
    public double reach() {
        double r = range.value;
        if (mc.player != null) r = Math.min(r, mc.player.entityInteractionRange());
        return r;
    }

    /** Same limit for the rocket dive's own range setting. */
    public double rocketReach() {
        double r = rocketAttackRange.value;
        if (mc.player != null) r = Math.min(r, mc.player.entityInteractionRange());
        return r;
    }

    private double distanceToBox(Entity e) {
        return Math.sqrt(e.getBoundingBox().distanceToSqr(mc.player.getEyePosition()));
    }

    @EventHandler
    public void onMotion(MotionEvent event) {
        if (event.getState() != MotionEvent.State.PRE) return;
        if (mc.player == null || mc.level == null) return;

        enforceSettings();
        updateFall();

        if (swapBackAt != 0L && !stayOnMace.value && savedSlot != -1 && System.currentTimeMillis() >= swapBackAt) {
            if (BlockPlacer.select(savedSlot)) {   // refused (a swap was already made this tick): try again next tick
                savedSlot = -1;
                swapBackAt = 0L;
            }
        }

        spearDtapHandler.onMotionPre();

        if (autoRocket.value) {
            rocketDive.tick();
        } else if (!rocketDive.idle()) {
            rocketDive.reset(true);
        }

        if (autoRocket.value && !rocketDive.idle()) return;
        if (mc.player.isFallFlying()) return;
        if (shieldBreakHandler.findAnyMaceSlot() == -1) return;

        double fallDist = fallStartY == -1 ? 0 : Math.max(0, fallStartY - mc.player.getY());

        if (!checkFalling.value && maceHit && mc.player.getAttackStrengthScale(0.0F) >= 1.0F) {
            maceHit = false;
            shieldBreakHandler.setSlamState(0);
        }

        if (checkFalling.value) {
            if (!isFalling || mc.player.getDeltaMovement().y >= -0.1) {
                return;
            }
            if (fallDist < minFallDistance.value) {
                return;
            }
        }

        Entity target = targeting.findOptimalTarget();
        if (target == null) {
            shieldBreakHandler.abortStaleSlam();
            return;
        }

        aimAt(targeting.calculateAdvancedRotations(mc.player, target), mc.player.distanceTo(target));

        if (distanceToBox(target) > reach()) return;   // aiming from further out, but never swing out of reach

        if (maceHit && retrySlam.value && isFalling && mc.player.getDeltaMovement().y < -0.1
                && fallDist >= minFallDistance.value && mc.player.getAttackStrengthScale(0.0F) >= 0.9F
                && System.currentTimeMillis() - lastMaceHitTime >= (long)retryDelay.value) {
            maceHit = false;
            shieldBreakHandler.setSlamState(0);
        }

        handleMaceAttack(target, fallDist);   // executeAttack() only swings when the crosshair is really on the target
    }

    /**
     * Turns the player towards the given angles, a bit each tick, like a hand on the mouse: a fraction of the remaining
     * error with a cap per tick, snapped to the mouse step size. It changes the player's real rotation directly (no
     * RotationEngine controller: that one wrote the old angle back over every turn, so the crosshair never reached the
     * target and no attack was ever allowed). Shared with the rocket dive.
     */
    public void aimAt(float[] rawRotations, double distance) {
        if (mc.player == null || mc.screen != null) return;

        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        float yawError = net.minecraft.util.Mth.wrapDegrees(rawRotations[0] - yaw);
        float pitchError = rawRotations[1] - pitch;

        if (distance < 0.8) {          // right on top of the target: ease off so the aim does not whip around
            float k = (float) (distance / 0.8);
            yawError *= k;
            pitchError *= k;
        }

        float yawStep = net.minecraft.util.Mth.clamp(yawError * 0.6f, -40.0f, 40.0f);
        float pitchStep = net.minecraft.util.Mth.clamp(pitchError * 0.6f, -25.0f, 25.0f);

        float[] snapped = targeting.applyGCDMouseFix(yaw, pitch, yaw + yawStep, pitch + pitchStep);
        float newYaw = snapped[0];
        float newPitch = net.minecraft.util.Mth.clamp(snapped[1], -90.0f, 90.0f);
        if (newYaw == yaw && newPitch == pitch) return;

        mc.player.setYRot(newYaw);
        mc.player.setXRot(newPitch);
        mc.player.yHeadRot = newYaw;
        mc.player.yRotO += newYaw - yaw;       // keep the camera interpolation smooth (same as a real mouse move)
        mc.player.xRotO += newPitch - pitch;
    }

    /** Lets go of the aim (rocket dive calls this when it ends). */
    public void releaseAim() {
        RotationEngine.INSTANCE.release();
    }

    private void updateFall() {
        boolean onGround = mc.player.onGround();
        double currentY = mc.player.getY();
        double yVel = mc.player.getDeltaMovement().y;

        if (onGround) {
            if (isFalling) resetFall();
            if (!stayOnMace.value && savedSlot != -1) {
                if (BlockPlacer.select(savedSlot)) {
                    savedSlot = -1;
                    swapBackAt = 0L;
                }
            }
            return;
        }

        if (fallStartY == -1 || currentY > fallStartY) fallStartY = currentY;

        if (yVel > 0.05) {
            if (maceHit) {
                maceHit = false;
                shieldBreakHandler.setSlamState(0);
            }
        } else if (yVel < -0.05) {
            if (!isFalling) {
                isFalling = true;
                maceHit = false;
                shieldBreakHandler.setSlamState(0);
            }
        }
    }

    private void resetFall() {
        isFalling = false;
        fallStartY = -1;
        maceHit = false;
        shieldBreakHandler.setSlamState(0);
    }

    private void handleMaceAttack(Entity target, double fallDist) {
        if (shieldBreakHandler.handleShieldBreak(target, fallDist)) return;

        if (!shieldBreakHandler.hasMace() && !maceHit) {
            if (savedSlot == -1) savedSlot = BlockPlacer.selected();
            if (autoSwitch.value && !shieldBreakHandler.switchToAppropriateMace(fallDist)) return;   // swap refused: next tick
        }

        if (shieldBreakHandler.hasMace() && !maceHit) {
            if (executeAttack(target)) registerMaceHit();
        }
    }

    /** True when the crosshair (the player's real look direction) is on the target and inside the reach limit. */
    public boolean crosshairOn(Entity target, double reach) {
        if (mc.player == null || target == null) return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());
        AABB box = target.getBoundingBox().inflate(target.getPickRadius());
        if (box.contains(eye)) return true;
        Optional<Vec3> hit = box.clip(eye, eye.add(look.scale(reach)));
        return hit.isPresent();
    }

    /**
     * Whether an attack could be made right now the way a player would make it: no screen open, only one attack per
     * tick, the target inside the reach limit and under the crosshair, and (optionally) the attack fully charged.
     */
    public boolean canAttack(Entity target, boolean needCharged) {
        if (mc.gameMode == null || mc.player == null || target == null || !target.isAlive()) return false;
        if (mc.screen != null) return false;
        if (mc.player.tickCount == lastAttackTick) return false;
        if (needCharged && requireCharge.value && mc.player.getAttackStrengthScale(0.5F) < 0.95F) return false;
        double reach = reach();
        if (distanceToBox(target) > reach) return false;
        return crosshairOn(target, reach);
    }

    public boolean executeAttack(Entity target) { return executeAttack(target, true); }

    /**
     * Attacks the way a click does: gameMode.attack() and then the swing, at most once per tick, only when
     * canAttack() agrees. Returns false (and sends nothing) otherwise, so the caller just tries again next tick.
     */
    public boolean executeAttack(Entity target, boolean needCharged) {
        if (!canAttack(target, needCharged)) return false;
        autoAttacking = true;
        try {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
            lastAttackTick = mc.player.tickCount;
            return true;
        } finally {
            autoAttacking = false;
        }
    }

    public static boolean isAutoAttacking() { return autoAttacking; }

    public void registerMaceHit() {
        maceHit = true;
        long now = System.currentTimeMillis();
        lastMaceHitTime = now;
        if (!stayOnMace.value && swapBackOnHit.value) swapBackAt = now + 50L;
    }

    // ---- Getters ----
    public Setting.Num getRange() { return range; }
    public Setting.Num getAimRange() { return aimRange; }
    public Setting.Num getMinFallDistance() { return minFallDistance; }
    public Setting.Num getDensityThreshold() { return densityThreshold; }
    public Setting.Bool getTargetPlayers() { return targetPlayers; }
    public Setting.Bool getTargetMobs() { return targetMobs; }
    public Setting.Bool getAutoSwitch() { return autoSwitch; }
    public ShieldMode getShieldMode() {
        if (!autoSwitch.value) return ShieldMode.NONE;   // both shield modes have to change weapon, so without Auto Switch they cannot run
        int v = (int) shieldMode.value;
        if (v == 1) return ShieldMode.STUN_SLAM;
        if (v == 2) return ShieldMode.DRAIN;
        return ShieldMode.NONE;
    }
    public Setting.Bool getFastStunSlam() { return fastStunSlam; }
    public Setting.Num getStunSlamDelay() { return stunSlamDelay; }

    public double rocketTargetRange() { return rocketTargetRange.value; }
    public double rocketAttackRange() { return rocketReach(); }
    public double rocketSnapFov() { return rocketSnapFov.value; }
    public double rocketHeightAbove() { return rocketHeightAbove.value; }
    public long rocketFireworkDelayMs() { return (long) rocketFireworkDelay.value; }
    public long rocketDropDelayMs() { return (long) rocketDropDelay.value; }
    public long rocketAttackDelayMs() { return (long) rocketAttackDelay.value; }
    public long rocketReequipDelayMs() { return (long) rocketReequipDelay.value; }
    public long rocketGlideDelayMs() { return (long) rocketGlideDelay.value; }
    public double rocketAimOffset() { return rocketAimOffset.value; }
    public boolean rocketLoop() { return rocketLoop.value; }
    public boolean targetPlayers() { return targetPlayers.value; }
    public boolean targetMobs() { return targetMobs.value; }
    public double minFall() { return minFallDistance.value; }
    public boolean rocketRecover() { return rocketRecover.value; }
    public boolean getRocketRecover() { return rocketRecover.value; }
    public Setting.Bool getSpearDtap() { return spearDtap; }

    public int findMaceSlot(double fall) { return shieldBreakHandler.findAnyMaceSlot(); }
    public int getSavedSlot() { return savedSlot; }
    public void setSavedSlot(int slot) { this.savedSlot = slot; }
    public boolean isMaceHit() { return maceHit; }
    public RocketDiveHandler getRocketDiveHandler() { return rocketDive; }
}