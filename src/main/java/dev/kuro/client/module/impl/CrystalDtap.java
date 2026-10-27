package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Rotations;
import dev.kuro.client.util.ServerSync;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Crystal Dtap: a one-key combo on the nearest enemy.
 *   launch (melee hit, or a wind charge at their feet)  ->  obsidian under them  ->  two crystals on it  ->  both crystals broken
 *   in the SAME tick (the double tap).
 *  - Switch it on (or press its key) with an enemy in range. It picks the nearest non-friend, non-bot player, plans the two
 *    crystal spots that hurt them most (an existing obsidian / bedrock block is used as it is, otherwise obsidian is placed first)
 *    and runs the steps one per tick: place obsidian, place crystals, then both hits go out together.
 *  - The plan is made after the launch, from where they are then, so it follows them into the air.
 *  - Needs in the hotbar (crystals may also be in the offhand): a weapon (sword / axe / mace / spear) for the melee launch or a
 *    wind charge for the other launch, obsidian (unless the spots are bedrock / existing obsidian) and 1-2 end crystals.
 *  - Silent aim (default on): each click turns the SERVER-side aim onto its target with a look packet; your camera never moves.
 *  - Same pacing as the other macros: one slot swap per tick, each placement waits for the server (ServerSync).
 *  - It switches itself off when finished and puts your old slot back.
 */
public class CrystalDtap extends Module {
    private static final double REACH = 4.5;
    private static final int MAX_AGE = 200;
    private static final int SERVER_TIMEOUT = 5;
    private static final int CRYSTAL_WAIT = 8;       // ticks to wait for a placed crystal to show up before trying again
    private static final int MAX_TRIES = 3;

    private final Setting.Num launch = add(new Setting.Num("Launch", 0, 0, 2, 1).labelled("Melee hit", "Wind charge", "None"));
    private final Setting.Num crystals = add(new Setting.Num("Crystals", 2, 1, 2, 1));
    private final Setting.Num targetRange = add(new Setting.Num("Target range", 5.0, 2.0, 8.0, 0.5));
    private final Setting.Num minDamage = add(new Setting.Num("Min damage", 6.0, 0.0, 36.0, 0.5));
    private final Setting.Bool limitSelf = add(new Setting.Bool("Limit self damage", false));
    private final Setting.Num maxSelf = add(new Setting.Num("Max self damage", 10.0, 0.0, 36.0, 0.5));
    private final Setting.Bool silentAim = add(new Setting.Bool("Silent aim", true));
    private final Setting.Num delay = add(new Setting.Num("Extra delay (ticks)", 0, 0, 10, 1));

    private enum Phase { IDLE, INIT, LAUNCH, LAUNCH_WAIT, PLAN, OBSIDIAN, CRYSTAL, BREAK, RESTORE }
    private Phase phase = Phase.IDLE;

    private static final class Spot {
        BlockPos obs, crystal;
        boolean existing;               // obsidian / bedrock is already there
        BlockHitResult obsHit;          // click that places the obsidian (null when existing)
        int obsTries, cClicks, cClickAge;
    }

    private Player target;
    private final List<Spot> spots = new ArrayList<>();
    private int origSlot, age, wait, launchTicks;
    private boolean swapped;

    public CrystalDtap() { super("Crystal Dtap", Category.COMBAT); }

    @Override
    protected void onEnable() {
        phase = Phase.INIT;
        age = 0; wait = 0; launchTicks = 0;
        swapped = false;
        target = null;
        spots.clear();
    }

    @Override
    protected void onDisable() {
        if (swapped && mc.player != null) BlockPlacer.selectForce(origSlot);
        Rotations.release(this);
        phase = Phase.IDLE;
        target = null;
        spots.clear();
        swapped = false;
    }

    // ---------------- the sequence ----------------

    @Override
    public void onPreTick() {
        if (phase == Phase.IDLE) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) { finish(); return; }
        if (phase == Phase.INIT) { init(); return; }
        if (mc.screen != null) return;
        if (target == null || !target.isAlive() || target.isRemoved()) { abort("The target is gone"); return; }
        if (++age > MAX_AGE) { abort("Gave up (took too long)"); return; }
        if (wait > 0) { wait--; return; }

        switch (phase) {
            case LAUNCH -> launch();
            case LAUNCH_WAIT -> launchWait();
            case PLAN -> plan();
            case OBSIDIAN -> obsidian();
            case CRYSTAL -> crystal();
            case BREAK -> breakCrystals();
            case RESTORE -> restore();
            default -> { }
        }
    }

    private void init() {
        if (mc.screen != null) { fail("Press its key bind in game (a menu is open)"); return; }
        target = bestTarget();
        if (target == null) { fail("No enemy player in range"); return; }
        if (crystalCount() < 1) { fail("No end crystals (hotbar or offhand)"); return; }
        if (launch.intValue() == 0 && weaponSlot() < 0 && !isWeapon(mc.player.getMainHandItem())) { fail("No weapon in your hotbar for the launch"); return; }
        if (launch.intValue() == 1 && BlockPlacer.findSlot(s -> s.is(Items.WIND_CHARGE)) < 0) { fail("No wind charge in your hotbar"); return; }
        origSlot = BlockPlacer.selected();
        wait = delay.intValue();
        phase = launch.intValue() == 2 ? Phase.PLAN : Phase.LAUNCH;
    }

    // ---- 1. launch ----

    private void launch() {
        if (launch.intValue() == 0) meleeLaunch(); else windLaunch();
    }

    private void meleeLaunch() {
        if (!isWeapon(mc.player.getMainHandItem())) {
            int slot = weaponSlot();
            if (slot < 0) { abort("No weapon in your hotbar"); return; }
            if (!ServerSync.ready(SERVER_TIMEOUT) || !BlockPlacer.select(slot)) return;
            ServerSync.beginStep();
            swapped = true;
            wait = delay.intValue();
            return;
        }
        if (reachTo(target) > 3.0) { abort("The target is out of melee reach"); return; }
        if (mc.player.getAttackStrengthScale(0.5f) < 0.9f) return;          // wait for the weapon to recharge
        look(target.position().add(0, target.getBbHeight() * 0.6, 0));
        AttackPackets.hit(target);
        launchTicks = 0;
        wait = Math.max(1, delay.intValue());
        phase = Phase.PLAN;
    }

    private void windLaunch() {
        if (!hold(s -> s.is(Items.WIND_CHARGE), "wind charges")) return;
        if (!ServerSync.ready(SERVER_TIMEOUT) || mc.player.isUsingItem()) return;
        look(target.position().add(0, 0.1, 0));                              // at their feet: the burst throws them up
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        ServerSync.beginStep();
        launchTicks = 0;
        phase = Phase.LAUNCH_WAIT;
    }

    /** Waits (a few ticks at most) until the wind charge has thrown the target up. */
    private void launchWait() {
        if (target.getDeltaMovement().y > 0.3 || !target.onGround() || ++launchTicks > 8) { wait = delay.intValue(); phase = Phase.PLAN; }
    }

    // ---- 2. plan the two crystal spots (from where the target is now) ----

    private void plan() {
        spots.clear();
        Vec3 eye = mc.player.getEyePosition();
        AABB me = mc.player.getBoundingBox(), them = target.getBoundingBox();
        BlockPos base = target.blockPosition();
        List<double[]> scored = new ArrayList<>();
        List<Spot> found = new ArrayList<>();
        for (int x = -3; x <= 3; x++) for (int y = -4; y <= 1; y++) for (int z = -3; z <= 3; z++) {
            BlockPos o = base.offset(x, y, z);
            BlockState st = mc.level.getBlockState(o);
            boolean existing = st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK);
            if (!existing && !st.canBeReplaced()) continue;
            BlockPos c = o.above();
            if (!mc.level.getBlockState(c).isAir()) continue;
            AABB obsBox = new AABB(o);
            if (!existing && (obsBox.intersects(me) || obsBox.intersects(them))) continue;    // a block cannot go inside a player
            if (!mc.level.getEntities((Entity) null, new AABB(c.getX(), c.getY(), c.getZ(), c.getX() + 1, c.getY() + 2, c.getZ() + 1)).isEmpty()) continue;
            Vec3 top = Vec3.atBottomCenterOf(c);
            if (eye.distanceToSqr(top) > REACH * REACH) continue;
            Vec3 cp = top.add(0, 0.5, 0);
            double dmg = crystalDamage(cp, target);
            double self = crystalDamage(cp, mc.player);
            if (dmg < minDamage.value) continue;
            if (limitSelf.value && self > maxSelf.value) continue;
            Spot sp = new Spot();
            sp.obs = o; sp.crystal = c; sp.existing = existing;
            if (!existing) {
                sp.obsHit = placementHit(o, true);
                if (sp.obsHit == null) sp.obsHit = placementHit(o, false);
                if (sp.obsHit == null) continue;
            }
            found.add(sp);
            scored.add(new double[] { found.size() - 1, dmg - self * 0.5 + (existing ? 1.0 : 0.0) });
        }
        scored.sort(Comparator.comparingDouble((double[] a) -> a[1]).reversed());
        int obsLeft = obsidianCount();
        for (double[] sc : scored) {
            if (spots.size() >= crystals.intValue()) break;
            Spot sp = found.get((int) sc[0]);
            if (!sp.existing) { if (obsLeft <= 0) continue; obsLeft--; }
            if (crystalCount() <= spots.size()) break;
            spots.add(sp);
        }
        if (spots.isEmpty()) { abort("No crystal spot hurts the target enough (or no obsidian)"); return; }
        phase = Phase.OBSIDIAN;
    }

    // ---- 3. obsidian ----

    private void obsidian() {
        for (Spot sp : spots) {
            BlockState st = mc.level.getBlockState(sp.obs);
            if (st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK)) continue;
            if (sp.obsTries >= MAX_TRIES) { abort("The obsidian could not be placed"); return; }
            if (!st.canBeReplaced()) { abort("A block got in the way"); return; }
            if (!hold(s -> s.is(Items.OBSIDIAN), "obsidian")) return;
            if (!ServerSync.ready(SERVER_TIMEOUT) || mc.player.isUsingItem() || mc.player.isSecondaryUseActive()) return;
            BlockHitResult hit = sp.obsHit;
            look(hit.getLocation());
            if (click(InteractionHand.MAIN_HAND, hit)) sp.obsTries++;
            return;                                                      // one step per tick
        }
        phase = Phase.CRYSTAL;
    }

    // ---- 4. crystals ----

    private void crystal() {
        boolean waiting = false;
        for (Spot sp : spots) {
            if (crystalAt(sp) != null) continue;
            if (sp.cClicks > 0 && age - sp.cClickAge < CRYSTAL_WAIT) { waiting = true; continue; }
            if (sp.cClicks >= MAX_TRIES) continue;                       // gave up on this one
            if (!mc.level.getBlockState(sp.obs).is(Blocks.OBSIDIAN) && !mc.level.getBlockState(sp.obs).is(Blocks.BEDROCK)) continue;
            InteractionHand hand = mc.player.getOffhandItem().is(Items.END_CRYSTAL) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            if (hand == InteractionHand.MAIN_HAND && !hold(s -> s.is(Items.END_CRYSTAL), "end crystals")) return;
            if (!ServerSync.ready(SERVER_TIMEOUT) || mc.player.isUsingItem() || mc.player.isSecondaryUseActive()) return;
            Vec3 top = Vec3.atCenterOf(sp.obs).add(0, 0.5, 0);
            BlockHitResult hit = new BlockHitResult(top, Direction.UP, sp.obs, false);
            look(top);
            if (click(hand, hit)) { sp.cClicks++; sp.cClickAge = age; }
            return;                                                      // one step per tick
        }
        if (!waiting) phase = Phase.BREAK;
    }

    // ---- 5. the double tap: every crystal is hit in the same tick ----

    private void breakCrystals() {
        List<EndCrystal> present = new ArrayList<>();
        for (Spot sp : spots) { EndCrystal c = crystalAt(sp); if (c != null) present.add(c); }
        if (present.isEmpty()) { abort("No crystal appeared"); return; }
        if (limitSelf.value) {
            for (EndCrystal c : present) {
                if (crystalDamage(c.position().add(0, 0.5, 0), mc.player) > maxSelf.value) {
                    Notifications.info("Crystal Dtap", "Left the crystals: too much damage to you", false);
                    phase = Phase.RESTORE;
                    return;
                }
            }
        }
        Vec3 aimPoint = present.get(0).position().add(0, 0.5, 0);
        look(aimPoint);
        for (EndCrystal c : present) AttackPackets.hit(c);               // all hits go out together, this tick
        Notifications.info("Crystal Dtap", present.size() + (present.size() == 1 ? " crystal" : " crystals") + " popped on " + target.getName().getString(), true);
        phase = Phase.RESTORE;
    }

    private void restore() {
        if (swapped && BlockPlacer.selected() != origSlot) {
            if (!ServerSync.ready(SERVER_TIMEOUT) || !BlockPlacer.select(origSlot)) return;
            ServerSync.beginStep();
        }
        swapped = false;
        finish();
    }

    // ---------------- helpers ----------------

    /** Makes sure the main hand holds a matching item: false = not yet (swapped this tick / refused / missing). */
    private boolean hold(Predicate<ItemStack> wanted, String what) {
        if (wanted.test(mc.player.getMainHandItem())) return true;
        int slot = BlockPlacer.findSlot(wanted);
        if (slot < 0) { abort("Ran out of " + what); return false; }
        if (!ServerSync.ready(SERVER_TIMEOUT) || !BlockPlacer.select(slot)) return false;
        ServerSync.beginStep();
        swapped = true;
        wait = delay.intValue();
        return false;
    }

    private static boolean isWeapon(ItemStack s) {
        return !s.isEmpty() && (s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(Items.MACE) || s.is(ItemTags.SPEARS));
    }

    private int weaponSlot() {
        int s = BlockPlacer.findSlot(i -> !i.isEmpty() && i.is(ItemTags.SWORDS));
        if (s < 0) s = BlockPlacer.findSlot(i -> !i.isEmpty() && i.is(ItemTags.AXES));
        if (s < 0) s = BlockPlacer.findSlot(i -> i.is(Items.MACE));
        if (s < 0) s = BlockPlacer.findSlot(i -> !i.isEmpty() && i.is(ItemTags.SPEARS));
        return s;
    }

    private int crystalCount() {
        int n = mc.player.getOffhandItem().is(Items.END_CRYSTAL) ? mc.player.getOffhandItem().getCount() : 0;
        for (int i = 0; i < 9; i++) { ItemStack s = mc.player.getInventory().getItem(i); if (s.is(Items.END_CRYSTAL)) n += s.getCount(); }
        return n;
    }

    private int obsidianCount() {
        int n = 0;
        for (int i = 0; i < 9; i++) { ItemStack s = mc.player.getInventory().getItem(i); if (s.is(Items.OBSIDIAN)) n += s.getCount(); }
        return n;
    }

    /** Nearest enemy player in range: friends and bots are skipped. */
    private Player bestTarget() {
        Player best = null;
        double bestD = targetRange.value;
        for (Player p : mc.level.getEntitiesOfClass(Player.class, mc.player.getBoundingBox().inflate(bestD), Entity::isAlive)) {
            if (p == mc.player || p.isSpectator() || Friends.shield(p) || AntiBot.isBot(p)) continue;
            double d = mc.player.distanceTo(p);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** Distance from your eyes to the closest point of the entity's hitbox (what melee reach is measured against). */
    private double reachTo(Entity e) {
        Vec3 eye = mc.player.getEyePosition();
        AABB b = e.getBoundingBox();
        Vec3 near = new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY, b.maxY), Mth.clamp(eye.z, b.minZ, b.maxZ));
        return eye.distanceTo(near);
    }

    private EndCrystal crystalAt(Spot sp) {
        AABB box = new AABB(sp.crystal).inflate(0.2);
        for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, box, Entity::isAlive)) return c;
        return null;
    }

    /** A click that places a block at c by pressing on a solid neighbour (never a container). Prefers a visible face. */
    private BlockHitResult placementHit(BlockPos c, boolean visibleOnly) {
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos s = c.relative(d);
            BlockState st = mc.level.getBlockState(s);
            if (st.canBeReplaced() || st.is(Blocks.RESPAWN_ANCHOR) || st.getBlock() instanceof BaseEntityBlock) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(s).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double dist = eye.distanceToSqr(hit);
            if (dist > REACH * REACH || dist >= bestD) continue;
            if (visibleOnly) {
                BlockHitResult r = mc.level.clip(new ClipContext(eye, hit, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                if (r.getType() != HitResult.Type.MISS && !r.getBlockPos().equals(s)) continue;
            }
            best = new BlockHitResult(hit, face, s, false);
            bestD = dist;
        }
        return best;
    }

    /** Turns the SERVER-side aim onto the point (a look packet, the camera stays put). */
    private void look(Vec3 point) {
        if (!silentAim.value) return;
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(h, 1.0E-4))), -90.0, 90.0);
        if (Rotations.set(this, yaw, pitch)) Rotations.sendLookPacket(true);
    }

    /** Vanilla use: MultiPlayerGameMode.useItemOn, swing if it was consumed. */
    private boolean click(InteractionHand hand, BlockHitResult hit) {
        if (!mc.level.getWorldBorder().isWithinBounds(hit.getBlockPos())) return false;
        InteractionResult r = mc.gameMode.useItemOn(mc.player, hand, hit);
        if (!r.consumesAction()) return false;
        mc.player.swing(hand);
        ServerSync.beginStep();
        return true;
    }

    /** Rough crystal damage estimate (same formula as Crystal Aura, not the full explosion calculator). */
    private static double crystalDamage(Vec3 crystal, LivingEntity e) {
        double dist = e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(crystal) / 12.0;
        if (dist > 1.0) return 0;
        double exposure = 1.0 - dist;
        double impact = exposure * exposure;
        return (impact * impact + impact) * 7.0 * 12.0 / 2.0 + 1.0;
    }

    /** Nothing was done yet: report and switch off. */
    private void fail(String why) {
        Notifications.info("Crystal Dtap", why, false);
        finish();
    }

    /** Stop part-way: put the slot back, then switch off. */
    private void abort(String why) {
        Notifications.info("Crystal Dtap", why, false);
        if (swapped) phase = Phase.RESTORE; else finish();
    }

    private void finish() {
        Rotations.release(this);
        phase = Phase.IDLE;
        if (isEnabled()) setEnabled(false);
    }
}
