package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.AssistAim;
import dev.kuro.client.util.CombatControl;
import dev.kuro.client.util.CombatReach;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.MovementHelper;
import dev.kuro.client.util.Rotations;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class SilentAura extends Module {
    private final Setting.Num range = add(new Setting.Num("Range", 3.0, 1.0, 6.0, 0.1));
    private final Setting.Num rotation = add(new Setting.Num("Rotation", 0, 0, 1, 1).labelled("Silent (F5 shows it)", "Camera (turns your view)"));
    private final Setting.Num strength = add(new Setting.Num("Aim strength", 6.0, 1.0, 10.0, 0.5));
    private final Setting.Bool vertical = add(new Setting.Bool("Aim vertically", true));
    private final Setting.Num humanize = add(new Setting.Num("Hand wobble %", 20, 0, 100, 5));
    private final Setting.Bool showModel = add(new Setting.Bool("Show in F5", true));
    private final Setting.Bool blatant = add(new Setting.Bool("Blatant mode", false));
    private final Setting.Num spinSpeed = add(new Setting.Num("Spin speed", 0.8, 0.2, 1.6, 0.05));
    private final Setting.Num spinDist = add(new Setting.Num("Spin distance", 2.5, 1.5, 3.0, 0.1));
    private final Setting.Num minCharge = add(new Setting.Num("Min Charge", 0.95, 0.0, 1.0, 0.05));
    private final Setting.Bool players = add(new Setting.Bool("Players", true));
    private final Setting.Bool mobs = add(new Setting.Bool("Mobs", false));
    private final Setting.Bool friends = add(new Setting.Bool("Attack Friends", false));

    private final AssistAim aim = new AssistAim();
    private LivingEntity target;
    private boolean spoof;
    private float sYaw, sPitch;
    private float sentYaw, sentPitch;
    private boolean sentKnown;

    public SilentAura() { super("Silent Aura", Category.COMBAT); }

    @Override protected void onEnable() { target = null; sentKnown = false; idle(); }
    @Override protected void onDisable() { target = null; idle(); }

    private void idle() {
        spoof = false;
        aim.reset();
        Rotations.release(this);
    }

    private boolean usable() {
        return mc.player != null && mc.level != null && mc.gameMode != null && mc.screen == null
                && !Freecam.active() && !mc.player.isSpectator() && mc.player.isAlive();
    }

    @Override
    public void onPostMotion() {
        if (mc.player == null) return;
        sentYaw = mc.player.getYRot();
        sentPitch = mc.player.getXRot();
        sentKnown = true;
    }

    @Override
    public void onPreTick() {
        if (!usable() || CombatControl.blocked(this)) { target = null; idle(); return; }

        target = findTarget();
        if (target == null) { idle(); return; }

        boolean silent = rotation.intValue() == 0;
        boolean legit = !blatant.value;
        if (silent && !Rotations.free() && !Rotations.ownedBy(this)) return;
        if (!sentKnown) { sentYaw = mc.player.getYRot(); sentPitch = mc.player.getXRot(); sentKnown = true; }

        Vec3 eye = mc.player.getEyePosition();
        // Aim at the exact center of the hitbox
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        double dist = Math.sqrt(targetCenter.distanceToSqr(eye));
        
        // Cap reach to 2.85 to prevent latency-induced Hitboxes flags
        double reach = Math.min(range.value, Math.min(2.85, mc.player.entityInteractionRange()));

        if (!silent && legit && canHit(dist, reach) && CombatReach.rayHitsLen(target, sentYaw, sentPitch, reach)) swing(target);

        if (silent) {
            if (!spoof) { sYaw = mc.player.getYRot(); sPitch = mc.player.getXRot(); }
        } else {
            sYaw = mc.player.getYRot(); sPitch = mc.player.getXRot();
        }

        float pull = blatant.value ? 10f : (float) strength.value;
        // Aim directly at targetCenter, not just target.position()
        float[] d = aim.step(target, sYaw, sPitch, 10.0, 10.0, vertical.value, pull, (float) humanize.value / 100f);
        if (d != null) {
            if (silent) {
                sYaw += d[0];
                sPitch = Mth.clamp(sPitch + d[1], -90f, 90f);
            } else {
                float y0 = mc.player.getYRot(), p0 = mc.player.getXRot();
                float ny = y0 + d[0], np = Mth.clamp(p0 + d[1], -90f, 90f);
                mc.player.setYRot(ny);
                mc.player.setXRot(np);
                mc.player.yHeadRot = ny;
                mc.player.yRotO += ny - y0;
                mc.player.xRotO += np - p0;
                sYaw = ny; sPitch = np;
            }
        }

        if (silent) {
            spoof = true;
            if (!Rotations.set(this, sYaw, sPitch)) return;
            Rotations.modelOwner = showModel.value ? this : null;
            
            if (blatant.value) {
                Rotations.sendLookPacket();
                if (canHit(dist, reach) && CombatReach.rayHitsLen(target, sYaw, sPitch, reach)) swing(target);
            } else {
                if (canHit(dist, reach) && CombatReach.rayHitsLen(target, sYaw, sPitch, reach)) {
                    float prevYaw = mc.player.getYRot();
                    float prevPitch = mc.player.getXRot();
                    
                    // Sync to silent angles.
                    mc.player.setYRot(sYaw);
                    mc.player.setXRot(sPitch);
                    swing(target);
                    
                    // Restore real view.
                    mc.player.setYRot(prevYaw);
                    mc.player.setXRot(prevPitch);
                }
            }
        } else {
            spoof = false;
            Rotations.release(this);
        }

        if (blatant.value) orbit(target);

        if (!silent && !legit) {
            if (!canHit(dist, reach) || !CombatReach.rayHitsLen(target, sYaw, sPitch, reach)) return;
            swing(target);
        }
    }

    private boolean canHit(double dist, double reach) {
        if (dist > reach) return false;
        if (mc.player.isUsingItem()) return false;
        if (mc.player.getAttackStrengthScale(0.5f) < minCharge.value) return false;
        return mc.player.hasLineOfSight(target);
    }

    private void swing(LivingEntity hitTarget) {
        if (hitTarget == null) return;
        mc.gameMode.attack(mc.player, hitTarget);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void orbit(LivingEntity t) {
        Vec3 me = mc.player.position(), c = t.position();
        double r = spinDist.value, sp = spinSpeed.value;
        double na = Math.atan2(me.z - c.z, me.x - c.x) + sp / r;
        double wx = c.x + Math.cos(na) * r, wz = c.z + Math.sin(na) * r;
        double vx = wx - me.x, vz = wz - me.z, len = Math.hypot(vx, vz), cap = sp * 1.3;
        if (len > cap) { vx *= cap / len; vz *= cap / len; }
        Vec3 mv = mc.player.getDeltaMovement();
        if (mc.player.onGround() && !MovementHelper.hasBlockBelow(mc.player, me.x + vx, me.z + vz)) return;
        double vy = mc.player.onGround() && mc.player.horizontalCollision ? 0.42 : mv.y;
        mc.player.setDeltaMovement(vx, vy, vz);
    }

    private LivingEntity findTarget() {
        Vec3 eye = mc.player.getEyePosition();
        double reach = Math.min(range.value, mc.player.entityInteractionRange());
        double aimRange = reach + 2.0;
        LivingEntity best = null;
        double bestD = aimRange;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || le == mc.player || !le.isAlive() || le.isSpectator()) continue;
            if (le instanceof Player p) {
                if (!players.value) continue;
                if (!friends.value && Friends.is(p)) continue;
                if (AntiBot.isBot(p)) continue;
            } else {
                if (!mobs.value || le instanceof ArmorStand) continue;
            }
            double d = Math.sqrt(le.getBoundingBox().distanceToSqr(eye));
            if (d < bestD) { bestD = d; best = le; }
        }
        return best;
    }
}