package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Projectile Aim: port of the "Aim Bot" for bow / crossbow / trident from the ru.white client.
 *
 *  - Picks the valid target nearest your crosshair.
 *  - Predicts where the target will be when the projectile lands (iterated flight-time estimate using the target's
 *    smoothed velocity), solves the launch angle with gravity 0.05 and drag 0.99 by bisection, and corrects for
 *    your own movement.
 *  - Turns your real view toward the solution every rendered frame, with horizontal / vertical smoothing and the
 *    mouse sensitivity GCD, so it looks like mouse movement.
 */
public class ProjectileAimBot extends Module {
    private final Setting.Bool onlyWhenUsing = add(new Setting.Bool("Only while charging", true));
    private final Setting.Bool players = add(new Setting.Bool("Players", true));
    private final Setting.Bool mobs = add(new Setting.Bool("Mobs", false));
    private final Setting.Bool friends = add(new Setting.Bool("Friends", false));
    private final Setting.Num range = add(new Setting.Num("Range", 30, 5, 80, 1));
    private final Setting.Num smoothH = add(new Setting.Num("Horizontal smoothing", 0.15, 0.01, 1.0, 0.01));
    private final Setting.Num smoothV = add(new Setting.Num("Vertical smoothing", 0.15, 0.01, 1.0, 0.01));
    private final Setting.Num predictMult = add(new Setting.Num("Predict strength", 1.0, 0.0, 2.0, 0.05));

    private static final double MOUSE_UNIT = 0.15;

    private final Map<UUID, Vec3[]> posHistory = new HashMap<>();
    private LivingEntity target;

    public ProjectileAimBot() { super("Projectile Aim", Category.COMBAT); }

    @Override
    protected void onDisable() {
        target = null;
        posHistory.clear();
    }

    // ----- per tick: velocity history + target pick -----------------------------------------------------

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null) { target = null; posHistory.clear(); return; }

        Set<UUID> seen = new HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity) || e == mc.player) continue;
            UUID id = e.getUUID();
            Vec3 pos = e.position();
            Vec3[] h = posHistory.computeIfAbsent(id, k -> new Vec3[]{pos, pos, pos});
            h[2] = h[1];
            h[1] = h[0];
            h[0] = pos;
            seen.add(id);
        }
        posHistory.keySet().retainAll(seen);

        if (onlyWhenUsing.value && projectileSpeed() <= 0f) {
            target = null;
        } else if (target == null || !valid(target)) {
            target = selectTarget();
        }
    }

    // ----- per frame: steer the camera ------------------------------------------------------------------

    @Override
    public void onHud(GuiGraphics g) {
        if (mc.player == null || mc.level == null || mc.screen != null || target == null) return;
        if (!valid(target)) { target = null; return; }

        float speed = projectileSpeed();
        if (speed <= 0f) return;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 base = target.position().add(0, target.getBbHeight() * 0.2, 0);
        Vec3 vel = smoothedVelocity(target);
        Vec3 predicted = predictPosition(eye, base, vel, speed);
        predicted = compensatePlayerVelocity(eye, predicted, speed);

        Aim aim = calcAim(eye, predicted, speed);
        if (aim == null) return;

        float newYaw = (float) Math.toDegrees(Math.atan2(-(predicted.x - eye.x), predicted.z - eye.z));
        double yawStep = Mth.wrapDegrees(newYaw - mc.player.getYRot()) * smoothH.value;
        double pitchStep = (aim.pitch - mc.player.getXRot()) * smoothV.value;

        double gcd = gcd();
        if (gcd > 1e-6) {
            yawStep = Math.round(yawStep / gcd) * gcd;
            pitchStep = Math.round(pitchStep / gcd) * gcd;
        }
        if (Math.abs(yawStep) < 1e-4 && Math.abs(pitchStep) < 1e-4) return;
        mc.player.turn(yawStep / MOUSE_UNIT, pitchStep / MOUSE_UNIT);
    }

    private double gcd() {
        try {
            double f = mc.options.sensitivity().get() * 0.6 + 0.2;
            return f * f * f * 1.2 * 0.15;
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ----- prediction -----------------------------------------------------------------------------------

    private Vec3 predictPosition(Vec3 from, Vec3 targetBase, Vec3 vel, float speed) {
        Vec3 predicted = targetBase;
        double mult = predictMult.value;
        for (int i = 0; i < 10; i++) {
            Aim aim = calcAim(from, predicted, speed);
            if (aim == null) break;
            Vec3 next = targetBase.add(vel.scale(aim.ticks * mult));
            if (next.distanceToSqr(predicted) < 0.001) break;
            predicted = next;
        }
        return predicted;
    }

    /** Pitch (Minecraft convention, negative = up) and flight time in ticks to hit "to" from "from", or null if out of reach. */
    private Aim calcAim(Vec3 from, Vec3 to, float speed) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double ux = d > 0.001 ? dx / d : 0.0;
        double uz = d > 0.001 ? dz / d : 1.0;
        if (d < 0.3) return new Aim(dy > 0.0 ? -89.0f : 89.0f, 5);

        double lo = -1.5607963267948965, hi = 1.5607963267948965;
        if (Double.isNaN(simYAtDist(from.y, ux, uz, speed, Math.toRadians(75.0), d))) return null;

        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) * 0.5;
            double simY = simYAtDist(from.y, ux, uz, speed, mid, d);
            if (Double.isNaN(simY) || simY < to.y) lo = mid; else hi = mid;
        }

        double elev = (lo + hi) * 0.5;
        double finalY = simYAtDist(from.y, ux, uz, speed, elev, d);
        if (Double.isNaN(finalY) || Math.abs(finalY - to.y) > 2.0) return null;

        float pitch = Mth.clamp((float) -Math.toDegrees(elev), -90f, 90f);
        return new Aim(pitch, simFlightTicks(ux, uz, speed, elev, d));
    }

    /** Height of the projectile when it has travelled targetD blocks horizontally; NaN if it never gets that far. */
    private double simYAtDist(double fromY, double ux, double uz, float speed, double elev, double targetD) {
        double cos = Math.cos(elev), sin = Math.sin(elev);
        double vx = ux * cos * speed, vy = sin * speed, vz = uz * cos * speed;
        double px = 0, py = fromY, pz = 0, prevHD = 0;

        for (int t = 0; t < 120; t++) {
            double npx = px + vx, npy = py + vy, npz = pz + vz;
            vy -= 0.05;
            vx *= 0.99; vy *= 0.99; vz *= 0.99;
            double hd = Math.sqrt(npx * npx + npz * npz);
            if (hd >= targetD) {
                double frac = prevHD < hd ? (targetD - prevHD) / (hd - prevHD) : 1.0;
                return py + (npy - py) * frac;
            }
            px = npx; py = npy; pz = npz;
            prevHD = hd;
            if (vy < -3.0) break;
        }
        return Double.NaN;
    }

    private int simFlightTicks(double ux, double uz, float speed, double elev, double targetD) {
        double cos = Math.cos(elev), sin = Math.sin(elev);
        double vx = ux * cos * speed, vy = sin * speed, vz = uz * cos * speed;
        double px = 0, pz = 0;
        for (int t = 1; t <= 120; t++) {
            px += vx; pz += vz;
            vy -= 0.05;
            vx *= 0.99; vy *= 0.99; vz *= 0.99;
            if (Math.sqrt(px * px + pz * pz) >= targetD) return t;
        }
        return 40;
    }

    /** The arrow inherits your motion, so aim against the drift you will have built up during the flight. */
    private Vec3 compensatePlayerVelocity(Vec3 from, Vec3 to, float speed) {
        Aim rough = calcAim(from, to, speed);
        if (rough == null) return to;
        int t = rough.ticks;
        double dragSum = t > 0 ? (1.0 - Math.pow(0.99, t)) / 0.01 : 0.0;
        Vec3 pv = mc.player.getDeltaMovement();
        return to.subtract(pv.x * dragSum, mc.player.onGround() ? 0.0 : pv.y * dragSum, pv.z * dragSum);
    }

    private Vec3 smoothedVelocity(LivingEntity e) {
        Vec3[] h = posHistory.get(e.getUUID());
        return h == null ? e.getDeltaMovement() : h[0].subtract(h[2]).scale(0.5);
    }

    // ----- held item ------------------------------------------------------------------------------------

    /** Launch speed of what you are drawing / holding ready, 0 when there is nothing to aim. */
    private float projectileSpeed() {
        if (mc.player == null) return 0f;
        if (mc.player.isUsingItem()) {
            Item item = mc.player.getUseItem().getItem();
            if (item instanceof BowItem) {
                float charge = mc.player.getTicksUsingItem() / 20.0f;
                charge = (charge * charge + charge * 2.0f) / 3.0f;
                charge = Math.min(charge, 1.0f);
                return Math.max(charge, 0.2f) * 3.0f;
            }
            if (item instanceof CrossbowItem) return 3.15f;
            if (item instanceof TridentItem) return 2.5f;
            return 0f;
        }
        // a loaded crossbow in either hand
        if (CrossbowItem.isCharged(mc.player.getMainHandItem()) || CrossbowItem.isCharged(mc.player.getOffhandItem())) return 3.15f;
        return 0f;
    }

    // ----- targets --------------------------------------------------------------------------------------

    private LivingEntity selectTarget() {
        LivingEntity best = null;
        double bestFov = Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f).normalize();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || !valid(le)) continue;
            Vec3 c = le.position().add(0, le.getBbHeight() * 0.2, 0);
            double fov = Math.acos(Mth.clamp(look.dot(c.subtract(eye).normalize()), -1.0, 1.0));
            if (fov < bestFov) { bestFov = fov; best = le; }
        }
        return best;
    }

    private boolean valid(LivingEntity e) {
        if (e == mc.player || !e.isAlive() || e.isInvulnerable() || e instanceof ArmorStand) return false;
        if (e.distanceToSqr(mc.player) > range.value * range.value) return false;
        if (e instanceof Player p) {
            if (!players.value || p.isSpectator() || p.isCreative()) return false;
            if (AntiBot.isBot(p)) return false;
            return friends.value || !Friends.is(p);
        }
        return mobs.value && e instanceof Mob;
    }

    private record Aim(float pitch, int ticks) {}
}
