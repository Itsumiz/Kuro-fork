package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.CombatControl;
import dev.kuro.client.util.CombatReach;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.Rotations;
import dev.kuro.client.util.SmoothLook;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Attack Aura: port of the "Attack Aura" module from the ru.white client.
 *
 *  - Target = the valid entity closest to your crosshair (by angle), kept while it stays valid.
 *  - Rotates inside "attack range + pre-aim range", hits inside "attack range" once the weapon is charged and
 *    the (silent) aim is actually on the hitbox.
 *  - Rotation modes: 1 Default (smooth, speeds from the old Matrix mode), 2 Snap (server only sees the angles on the
 *    hit tick), 3 HvH (instant lock), 4 Camera (turns your real view).
 *  - Goes through Auto Weapon (weapon pick / shield axe / mace) and AttackPackets (packet order), like Kill Aura.
 *
 * Not ported: the ru.white server-specific rotation profiles (FunTime, SpookyTime), the sprint-packet tricks (use
 * the WTap module), the movement-correction event and the shattering cube / ghost / ring target ESPs (they rely on
 * that client's own render pipelines). A box target ESP built on WorldGfx replaces them.
 */
public class AttackAura extends Module {
    private final Setting.Num attackRange = add(new Setting.Num("Attack range", 3.0, 2.5, 6.0, 0.1));
    private final Setting.Num preRange = add(new Setting.Num("Pre-aim range", 1.0, 0.0, 3.0, 0.1));
    private final Setting.Bool players = add(new Setting.Bool("Players", true));
    private final Setting.Bool naked = add(new Setting.Bool("Naked players", true));
    private final Setting.Bool mobs = add(new Setting.Bool("Mobs", false));
    private final Setting.Bool friends = add(new Setting.Bool("Friends", false));
    private final Setting.Num rotation = add(new Setting.Num("Rotation (1-4)", 1, 1, 4, 1));
    private final Setting.Num cameraSpeed = add(new Setting.Num("Camera speed", 12, 2, 60, 1));
    private final Setting.Num minCharge = add(new Setting.Num("Min charge", 0.95, 0.1, 1.0, 0.05));
    private final Setting.Bool smartCrits = add(new Setting.Bool("Smart crits", false));
    private final Setting.Bool throughWalls = add(new Setting.Bool("Hit through blocks", false));
    private final Setting.Bool pauseEating = add(new Setting.Bool("Pause while using item", false));
    private final Setting.Bool esp = add(new Setting.Bool("Target box", true));
    private final Setting.Hue espColor = add(new Setting.Hue("Target colour", 0.0f).wheel(0.7f, 1f));

    private static AttackAura instance;

    private LivingEntity target;
    private boolean spoof;
    private float yaw, pitch;                 // server-side angles while spoofing
    private double speedYaw = 34, speedPitch = 12;
    private int critWait;

    public AttackAura() { super("Attack Aura", Category.COMBAT); instance = this; }

    public static AttackAura get() { return instance; }
    public static LivingEntity currentTarget() { return instance != null && instance.isEnabled() ? instance.target : null; }

    @Override protected void onEnable() { idle(); target = null; }
    @Override protected void onDisable() { idle(); target = null; }

    private void idle() {
        spoof = false;
        critWait = 0;
        Rotations.release(this);
    }

    private boolean usable() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return false;
        if (mc.screen != null || Freecam.active() || mc.player.isSpectator()) return false;
        return !(pauseEating.value && mc.player.isUsingItem());
    }

    private double detectRange() { return attackRange.value + preRange.value; }

    // ----- rotation (before the movement packet) -------------------------------------------------------

    @Override
    public void onPreTick() {
        if (!usable() || CombatControl.blocked(this)) { idle(); target = null; return; }

        if (target == null || !valid(target)) target = pickTarget();
        if (target == null) { idle(); return; }

        int mode = rotation.intValue();
        float realYaw = mc.player.getYRot(), realPitch = mc.player.getXRot();
        if (!spoof) { yaw = realYaw; pitch = realPitch; }

        float[] want = anglesTo(aimPoint(target));

        switch (mode) {
            case 2 -> {                                   // Snap: only show the aim while a hit is about to go out
                if (ready()) {
                    spoof = true;
                    yaw = want[0];
                    pitch = want[1];
                    Rotations.set(this, yaw, pitch);
                } else {
                    idle();
                }
            }
            case 3 -> {                                   // HvH: lock straight onto the aim point
                spoof = true;
                yaw = want[0];
                pitch = want[1];
                Rotations.set(this, yaw, pitch);
            }
            case 4 -> {                                   // Camera: turn the real view, nothing silent
                idle();
                SmoothLook.step(target, cameraSpeed.value);
            }
            default -> {                                  // Default: smooth silent aim
                spoof = true;
                steer(want[0], want[1], rayOnTarget(yaw, pitch));
                Rotations.set(this, yaw, pitch);
            }
        }
    }

    /** Smooth approach (speeds and easing taken from the old Matrix rotation): fast far away, almost still on target. */
    private void steer(float wantYaw, float wantPitch, boolean onTarget) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double ty = onTarget ? 3 + r.nextDouble() : 50 + r.nextDouble() * 30;
        double tp = onTarget ? 1 + r.nextDouble() : 7 + r.nextDouble() * 5;
        speedYaw += (ty - speedYaw) * 0.3;
        speedPitch += (tp - speedPitch) * 0.3;

        double errYaw = Mth.wrapDegrees(wantYaw - yaw);
        double errPitch = wantPitch - pitch;
        double stepYaw = Mth.clamp(errYaw, -speedYaw, speedYaw);
        double stepPitch = Mth.clamp(errPitch, -speedPitch, speedPitch);

        double gcd = gcd();
        if (gcd > 1e-6) {
            stepYaw = Math.round(stepYaw / gcd) * gcd;
            stepPitch = Math.round(stepPitch / gcd) * gcd;
        }
        yaw += (float) stepYaw;
        pitch = Mth.clamp(pitch + (float) stepPitch, -90f, 90f);
    }

    private double gcd() {
        try {
            double f = mc.options.sensitivity().get() * 0.6 + 0.2;
            return f * f * f * 1.2 * 0.15;
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ----- attack (end of tick, queued to go out before the next movement packet) ---------------------

    @Override
    public void onTick() {
        if (!usable() || CombatControl.blocked(this) || target == null || !target.isAlive()) return;

        AutoWeapon.onTarget(target);
        boolean weaponStep = AutoWeapon.bypass(target);
        if (!weaponStep && AutoWeapon.progress() < minCharge.value) return;
        if (!inAttackRange(target)) return;
        if (!throughWalls.value && !mc.player.hasLineOfSight(target)) return;
        if (!aimed(target)) return;
        if (smartCrits.value && waitingForCrit()) return;
        if (!AutoWeapon.readyToAttack()) return;

        final double reach = attackRange.value;
        AttackPackets.sequence(target,
                e -> e instanceof LivingEntity le && le.isAlive() && CombatReach.eyeDistance(le) <= reach + 0.5,
                e -> {
                    if (Rotations.active() && Rotations.ownedBy(this)) Rotations.sendLookPacket(true);
                    AttackPackets.sendNow(e);
                });
        critWait = 0;
    }

    /** Smart crits: do not swing while rising in the air (a crit needs you to be falling); never wait more than 8 ticks. */
    private boolean waitingForCrit() {
        boolean canCrit = !mc.player.onGround() && !mc.player.isInWater() && !mc.player.onClimbable()
                && !mc.player.isPassenger();
        if (!canCrit) return false;
        if (mc.player.getDeltaMovement().y > 0 && critWait < 8) { critWait++; return true; }
        return false;
    }

    private boolean ready() {
        return target != null && (AutoWeapon.bypass(target) || AutoWeapon.progress() >= minCharge.value);
    }

    private boolean inAttackRange(LivingEntity e) { return CombatReach.eyeDistance(e) <= attackRange.value; }

    private boolean aimed(LivingEntity e) {
        boolean silent = Rotations.active() && Rotations.ownedBy(this);
        return rayOnTarget(silent ? Rotations.yaw() : mc.player.getYRot(), silent ? Rotations.pitch() : mc.player.getXRot());
    }

    private boolean rayOnTarget(float y, float p) {
        return target != null && CombatReach.rayHitsLen(target, y, p, attackRange.value);
    }

    // ----- target selection ---------------------------------------------------------------------------

    private LivingEntity pickTarget() {
        LivingEntity best = null;
        double bestAngle = Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f).normalize();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || !valid(le)) continue;
            Vec3 to = le.position().add(0, le.getBbHeight() * 0.5, 0).subtract(eye).normalize();
            double angle = Math.acos(Mth.clamp(look.dot(to), -1.0, 1.0));
            if (angle < bestAngle) { bestAngle = angle; best = le; }
        }
        return best;
    }

    private boolean valid(LivingEntity e) {
        if (e == mc.player || !e.isAlive() || e.isRemoved() || e instanceof ArmorStand) return false;
        if (e.isInvulnerable()) return false;
        if (CombatReach.eyeDistance(e) > detectRange()) return false;
        if (!throughWalls.value && !mc.player.hasLineOfSight(e)) return false;
        if (AntiBot.isBot(e)) return false;
        if (Friends.is(e) && !friends.value) return false;
        if (e instanceof Player p) {
            if (!players.value || p.isSpectator() || p.isCreative()) return false;
            if (!naked.value && p.getArmorValue() <= 0) return false;
            return true;
        }
        return mobs.value && e instanceof Mob;
    }

    // ----- aim point ----------------------------------------------------------------------------------

    /** Point on the hitbox closest to your eyes, pulled toward the middle of the body, with a slow sway. */
    private Vec3 aimPoint(LivingEntity t) {
        AABB b = t.getBoundingBox();
        Vec3 eye = mc.player.getEyePosition();
        double w = b.getXsize(), h = b.getYsize();
        double cx = Mth.clamp(eye.x, b.minX + w * 0.2, b.maxX - w * 0.2);
        double cz = Mth.clamp(eye.z, b.minZ + w * 0.2, b.maxZ - w * 0.2);
        double cy = Mth.clamp(eye.y, b.minY + h * 0.25, b.maxY - h * 0.15);
        double midX = (b.minX + b.maxX) / 2, midY = b.minY + h * 0.65, midZ = (b.minZ + b.maxZ) / 2;
        double time = System.currentTimeMillis();
        return new Vec3(
                midX + (cx - midX) * 0.5 + 0.08 * Math.sin(time / 250.0),
                midY + (cy - midY) * 0.5 + h * 0.04 * Math.cos(time / 850.0),
                midZ + (cz - midZ) * 0.5 + 0.08 * Math.cos(time / 310.0));
    }

    private float[] anglesTo(Vec3 to) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = to.x - eye.x, dy = to.y - eye.y, dz = to.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float y = h > 0.01 ? (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0) : yaw;
        float p = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(h, 1e-4))), -90.0, 90.0);
        return new float[]{y, p};
    }

    // ----- target ESP ---------------------------------------------------------------------------------

    @Override
    public void onWorld() {
        if (!esp.value || target == null || !target.isAlive()) return;
        float pt = Projector.tickProgress;
        Vec3 p = target.getPosition(pt);
        Vec3 now = target.position();
        AABB b = target.getBoundingBox().move(p.x - now.x, p.y - now.y, p.z - now.z);
        boolean hurt = target.hurtTime > 0;
        int rgb = hurt ? 0xFF4848 : espColor.rgb();
        WorldGfx.shadedBox(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, rgb, 50, 0xFF000000 | rgb, 2, true);
    }
}
