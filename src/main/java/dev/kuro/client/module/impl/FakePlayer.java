package dev.kuro.client.module.impl;

import com.mojang.authlib.GameProfile;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.DebugChat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Fake Player: a client-side dummy player for testing combat modules without a server or a second account.
 *
 * It only exists on YOUR client (negative entity id, never sent anywhere). Attacks on it are intercepted in
 * MultiPlayerGameMode.attack (see ClientPlayerInteractionManagerMixin), so nothing is sent to the server; the damage,
 * knockback, hurt flash and death are simulated here. Every module that looks at level players (Silent Aura, Kill Aura,
 * Nametags, Tracers, Hitboxes ...) sees it like a normal player.
 *
 * Simulated damage = your attack-damage attribute (so the held weapon counts) scaled by the attack charge, x1.5 on a
 * falling crit, then reduced by the dummy's armour. Enchantments such as Sharpness are not simulated.
 */
public class FakePlayer extends Module {
    private static final int FAKE_ID = -80421;
    private static FakePlayer instance;

    private String dummyName = "Dummy";
    private final Setting.Text nameBox = add(new Setting.Text("Name (type, Enter)", n -> {
        dummyName = n.length() > 16 ? n.substring(0, 16) : n;
        if (isEnabled()) spawn();
    }));
    private final Setting.Num health = add(new Setting.Num("Health", 20, 1, 40, 1));
    private final Setting.Bool copyGear = add(new Setting.Bool("Copy My Gear", true));
    private final Setting.Num motion = add(new Setting.Num("Motion", 0, 0, 2, 1).labelled("Static", "Strafe", "Follow"));
    private final Setting.Num moveSpeed = add(new Setting.Num("Move Speed", 0.25, 0.05, 0.6, 0.05));
    private final Setting.Num radius = add(new Setting.Num("Distance", 3.0, 1.5, 6.0, 0.1));
    private final Setting.Bool knockback = add(new Setting.Bool("Knockback", true));
    private final Setting.Bool respawn = add(new Setting.Bool("Respawn On Death", true));
    private final Setting.Bool damageLog = add(new Setting.Bool("Damage In Chat", true));
    private final Setting.Button respawnNow = add(new Setting.Button("Respawn Now", () -> { if (isEnabled() && mc.level != null) spawn(); }));

    private RemotePlayer fake;
    private ClientLevel fakeLevel;
    private Vec3 anchor = Vec3.ZERO;
    private Vec3 vel = Vec3.ZERO;
    private double orbit;
    private int hurtCooldown;
    private int deadTicks = -1;

    public FakePlayer() {
        super("Fake Player", Category.MISC);
        instance = this;
    }

    // ---------------------------------------------------------------- hooks used elsewhere

    /** True for the dummy (AntiBot / LogoutSpots use this so they do not treat it as a bot or a logout). */
    public static boolean isFake(Entity e) {
        return instance != null && instance.fake != null && e == instance.fake;
    }

    /**
     * Called from MultiPlayerGameMode.attack. Returns true when the target is the dummy: the real attack is then cancelled
     * (no packet leaves the client) and the hit is simulated here.
     */
    public static boolean onAttack(Entity target) {
        if (instance == null || !instance.isEnabled() || instance.fake == null || target != instance.fake) return false;
        instance.hit();
        return true;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onEnable() {
        if (mc.level == null || mc.player == null) { setEnabled(false); return; }
        spawn();
    }

    @Override
    protected void onDisable() {
        remove();
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (fake == null || fakeLevel != mc.level || fake.isRemoved()) {   // joined another world: the old entity is gone
            spawn();
            return;
        }

        if (hurtCooldown > 0) hurtCooldown--;

        if (deadTicks >= 0) {                                              // death: let it fall over, then respawn / switch off
            if (++deadTicks >= 25) {
                remove();
                if (respawn.value) spawn(); else setEnabled(false);
            }
            return;
        }

        moveFake();
    }

    private void spawn() {
        remove();
        if (mc.level == null || mc.player == null) return;

        fake = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), dummyName));
        fake.setId(FAKE_ID);
        fakeLevel = mc.level;

        Vec3 look = mc.player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : flat.normalize();
        anchor = mc.player.position().add(flat.scale(radius.value));
        vel = Vec3.ZERO;
        orbit = Math.atan2(anchor.z - mc.player.getZ(), anchor.x - mc.player.getX());
        hurtCooldown = 0;
        deadTicks = -1;

        place(anchor.x, anchor.y, anchor.z);
        faceTowards(mc.player.getX(), mc.player.getZ());
        fake.xo = anchor.x; fake.yo = anchor.y; fake.zo = anchor.z;
        fake.setOnGround(true);

        AttributeInstance max = fake.getAttribute(Attributes.MAX_HEALTH);
        if (max != null) max.setBaseValue(health.value);
        fake.setHealth((float) health.value);

        if (copyGear.value) {
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
                fake.setItemSlot(s, mc.player.getItemBySlot(s).copy());
            }
        }

        mc.level.addEntity(fake);
    }

    private void remove() {
        if (fake != null && mc.level != null && fakeLevel == mc.level) {
            mc.level.removeEntity(FAKE_ID, Entity.RemovalReason.DISCARDED);
        }
        fake = null;
        fakeLevel = null;
        deadTicks = -1;
    }

    // ---------------------------------------------------------------- movement (RemotePlayer has no physics of its own)

    private void moveFake() {
        Vec3 me = mc.player.position();
        Vec3 pos = fake.position();
        double floorY = anchor.y;

        int mode = motion.intValue();
        if (mode == 1) {                                     // Strafe: circles you at the set distance
            orbit += moveSpeed.value / Math.max(1.0, radius.value);
            double tx = me.x + Math.cos(orbit) * radius.value;
            double tz = me.z + Math.sin(orbit) * radius.value;
            pos = new Vec3(pos.x + (tx - pos.x) * 0.35, pos.y, pos.z + (tz - pos.z) * 0.35);
            floorY = me.y;
        } else if (mode == 2) {                              // Follow: walks up to you and stops at the set distance
            double dx = me.x - pos.x, dz = me.z - pos.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            double stop = Math.min(radius.value, 2.0);
            if (d > stop) {
                double step = Math.min(moveSpeed.value, d - stop);
                pos = new Vec3(pos.x + dx / d * step, pos.y, pos.z + dz / d * step);
            }
            floorY = me.y;
        }

        // simulated knockback + a simple gravity down to the floor height
        vel = new Vec3(vel.x * 0.6, (vel.y - 0.08) * 0.98, vel.z * 0.6);
        double y = pos.y + vel.y;
        boolean grounded = false;
        if (y <= floorY) { y = floorY; vel = new Vec3(vel.x, 0, vel.z); grounded = true; }
        pos = new Vec3(pos.x + vel.x, y, pos.z + vel.z);

        place(pos.x, pos.y, pos.z);
        fake.setOnGround(grounded);
        faceTowards(me.x, me.z);
    }

    private void place(double x, double y, double z) {
        fake.setPos(x, y, z);   // the level tick copies the previous position to xo/yo/zo, so rendering interpolates
    }

    private void faceTowards(double x, double z) {
        float yaw = (float) Math.toDegrees(Math.atan2(z - fake.getZ(), x - fake.getX())) - 90f;
        fake.setYRot(yaw);
        fake.setXRot(0f);
        fake.yHeadRot = yaw;
        fake.yBodyRot = yaw;
        if (fake.tickCount == 0) {              // first placement: no smoothing from a stale angle
            fake.yRotO = yaw; fake.xRotO = 0f; fake.yHeadRotO = yaw; fake.yBodyRotO = yaw;
        }
    }

    // ---------------------------------------------------------------- damage simulation

    private void hit() {
        if (deadTicks >= 0 || hurtCooldown > 0) { mc.player.resetAttackStrengthTicker(); return; }

        float charge = mc.player.getAttackStrengthScale(0.5f);
        double dmg = mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (0.2 + charge * charge * 0.8);
        boolean crit = charge > 0.9f && mc.player.fallDistance > 0 && !mc.player.onGround()
                && !mc.player.isInWater() && !mc.player.isPassenger() && !mc.player.isSprinting();
        if (crit) dmg *= 1.5;

        // vanilla armour formula
        double armor = fake.getArmorValue();
        double tough = fake.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        double eff = Mth.clamp(armor - dmg / (2.0 + tough / 4.0), armor * 0.2, 20.0);
        dmg *= 1.0 - eff / 25.0;

        float before = fake.getHealth();
        fake.setHealth(Math.max(0f, before - (float) dmg));
        fake.animateHurt(mc.player.getYRot());
        hurtCooldown = 10;

        if (knockback.value) {
            Vec3 away = fake.position().subtract(mc.player.position());
            away = new Vec3(away.x, 0, away.z);
            away = away.lengthSqr() < 1e-6 ? mc.player.getLookAngle() : away.normalize();
            double kb = mc.player.isSprinting() ? 0.9 : 0.4;
            vel = new Vec3(away.x * kb, 0.36, away.z * kb);
        }

        mc.player.resetAttackStrengthTicker();   // vanilla attack() resets the charge; we cancelled it

        if (damageLog.value) {
            DebugChat.say("Fake Player", String.format("hit for %.1f%s  (%.1f / %.0f hp)",
                    dmg, crit ? " (crit)" : "", fake.getHealth(), health.value));
        }
        if (fake.getHealth() <= 0f) {
            deadTicks = 0;
            if (damageLog.value) DebugChat.say("Fake Player", "killed");
        }
    }
}
