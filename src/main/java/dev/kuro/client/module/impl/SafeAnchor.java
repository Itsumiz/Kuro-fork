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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Safe Anchor: a one-key macro. Look at the block you want the anchor on and switch it on (or press its key):
 *   place anchor  ->  charge it with glowstone  ->  place a glowstone block as cover  ->  detonate.
 *  - "Place near player" (default on): the nearest enemy player in range is the target and the anchor goes on the free, clickable
 *    spot closest to their body that you can see and reach (never inside you or them). With the setting off it goes where a normal
 *    right-click on your crosshair would put it; if you are not aimed at a block (or there is no spot near them), it goes on the
 *    block nearest to the enemy that you can reach.
 *  - "Silent aim" (default on): the aim moves server side only (Rotations, a look packet before each click), turning smoothly
 *    toward the exact point it clicks, aim-assist style (a share of the remaining angle per tick, capped by "Aim speed"). A click
 *    waits until the aim is on the point. Your own camera never moves. Off = clicks go out with your real view like before.
 *  - The cover block is picked automatically: of the free spots around the anchor it takes the one that shields your body
 *    the most (explosion damage is scaled by how much of you the blast can "see" from the anchor, a solid block in the
 *    way cuts that). It is placed against a solid block that is NOT the anchor, so it is never mistaken for another charge.
 *  - If there is no spot for the cover (or it cannot be placed), it detonates anyway. "Require cover" (default off) makes it
 *    stop before detonating and leave the anchor charged instead.
 *  - Needs in the hotbar: a Respawn Anchor and at least 2 Glowstone blocks (1 charge + 1 cover). Not in the Nether.
 *  - Same pacing as Anchor Bomb: every right-click is the game's own useItemOn, one step per tick, each step waits for
 *    the server's answer (ServerSync). Switches itself off when finished and puts your old slot back.
 */
public class SafeAnchor extends Module {
    private static final int MAX_AGE = 160;
    private static final int SERVER_TIMEOUT = 5;
    private static final int MAX_RETRIES = 3;
    private static final double REACH = 4.5;

    private static SafeAnchor instance;

    private final Setting.Bool nearPlayer = add(new Setting.Bool("Place near player", true));
    private final Setting.Num targetRange = add(new Setting.Num("Target range", 6.0, 2.0, 8.0, 0.5));
    private final Setting.Bool silentAim = add(new Setting.Bool("Silent aim", true));
    private final Setting.Num aimSpeed = add(new Setting.Num("Aim speed (deg/tick)", 45, 5, 180, 5));
    private final Setting.Bool requireCover = add(new Setting.Bool("Require cover", false));
    private final Setting.Num delay = add(new Setting.Num("Extra delay (ticks)", 0, 0, 10, 1));

    private enum Phase { IDLE, INIT, PLACE, PLACE_WAIT, CHARGE, CHARGE_WAIT, COVER, COVER_WAIT, EXPLODE, RESTORE }
    private Phase phase = Phase.IDLE;
    private BlockHitResult placeHit;
    private BlockPos pos, coverPos;
    private int origSlot, age, wait, retries;
    private boolean swapped;
    private double exposureAfter = 1.0;
    private Player target;
    private float curYaw = Float.NaN, curPitch = Float.NaN;   // where the server-side aim is right now

    public SafeAnchor() { super("Safe Anchor", Category.COMBAT); instance = this; }

    /** True while the macro is working (Anchor Bomb stays out of its way). */
    public static boolean running() { return instance != null && instance.phase != Phase.IDLE; }

    @Override
    protected void onEnable() {
        phase = Phase.INIT;
        age = 0; wait = 0; retries = 0;
        swapped = false;
        pos = null; coverPos = null;
        target = null;
        curYaw = Float.NaN; curPitch = Float.NaN;
    }

    @Override
    protected void onDisable() {
        if (swapped && mc.player != null) BlockPlacer.selectForce(origSlot);
        Rotations.release(this);
        phase = Phase.IDLE;
        pos = null; coverPos = null; target = null;
        swapped = false;
    }

    // ---------------- the sequence ----------------

    @Override
    public void onPreTick() {
        if (phase == Phase.IDLE) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) { finish(); return; }
        if (phase == Phase.INIT) { init(); return; }
        if (mc.screen != null) return;
        if (++age > MAX_AGE) { abort("Gave up (took too long)"); return; }
        if (wait > 0) { wait--; return; }
        if (phase != Phase.PLACE && phase != Phase.PLACE_WAIT && phase != Phase.RESTORE && !isAnchor()) { abort("The anchor is gone"); return; }

        switch (phase) {
            case PLACE -> place();
            case PLACE_WAIT -> placeWait();
            case CHARGE -> charge();
            case CHARGE_WAIT -> chargeWait();
            case COVER -> cover();
            case COVER_WAIT -> coverWait();
            case EXPLODE -> explode();
            case RESTORE -> restore();
            default -> { }
        }
    }

    private void init() {
        if (mc.screen != null) { fail("Press its key bind in game (a menu is open)"); return; }
        if (mc.level.dimension() == Level.NETHER) { fail("Anchors do not explode in the Nether"); return; }
        if (BlockPlacer.findSlot(s -> s.is(Items.RESPAWN_ANCHOR)) < 0) { fail("No Respawn Anchor in your hotbar"); return; }
        int need = requireCover.value ? 2 : 1;
        if (glowstoneCount() < need) { fail(need == 2 ? "Needs 2 glowstone blocks in your hotbar (charge + cover)" : "No glowstone in your hotbar"); return; }

        BlockHitResult hit = null;
        BlockPos spot = null;
        target = bestTarget();
        if (nearPlayer.value && target != null) {
            hit = spotNear(target);
            if (hit != null) spot = hit.getBlockPos().relative(hit.getDirection());
        }
        if (spot == null && mc.hitResult instanceof BlockHitResult ch && ch.getType() == HitResult.Type.BLOCK) {
            BlockPos clicked = ch.getBlockPos();
            BlockPos s2 = mc.level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(ch.getDirection());
            if (!mc.level.getBlockState(s2).canBeReplaced()) { fail("That spot is taken"); return; }
            if (new AABB(s2).intersects(mc.player.getBoundingBox())) { fail("The anchor would be inside you"); return; }
            if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(s2)) > REACH * REACH) { fail("Too far away"); return; }
            hit = ch;
            spot = s2;
        }
        if (spot == null && target != null) {                // not aimed at a block: the block nearest to the enemy that you can reach
            hit = spotNear(target);
            if (hit != null) spot = hit.getBlockPos().relative(hit.getDirection());
        }
        if (spot == null) { fail(target == null ? "Look at a block (or get a player in range)" : "No block near the enemy is in reach"); return; }

        placeHit = hit;
        pos = spot;
        origSlot = BlockPlacer.selected();
        wait = delay.intValue();
        phase = Phase.PLACE;
    }

    private void place() {
        if (!hold(Items.RESPAWN_ANCHOR)) return;
        if (!mc.level.getBlockState(pos).canBeReplaced()) { abort("The spot got taken"); return; }
        if (!aim(placeHit.getLocation())) return;
        if (!canClick()) return;
        if (click(placeHit)) { wait = delay.intValue(); phase = Phase.PLACE_WAIT; }
    }

    private void placeWait() {
        if (!ServerSync.confirmed(SERVER_TIMEOUT)) return;
        if (isAnchor()) { phase = Phase.CHARGE; return; }
        if (++retries > MAX_RETRIES) { abort("The server did not accept the anchor"); return; }
        phase = Phase.PLACE;
    }

    private void charge() {
        if (!hold(Items.GLOWSTONE)) return;
        BlockHitResult hit = anchorHit();
        if (!aim(hit.getLocation())) return;
        if (!canClick()) return;
        if (click(hit)) { wait = delay.intValue(); phase = Phase.CHARGE_WAIT; }
    }

    private void chargeWait() {
        if (!ServerSync.confirmed(SERVER_TIMEOUT)) return;
        if (chargeLevel() > 0) { retries = 0; planCover(); return; }
        if (++retries > MAX_RETRIES) { abort("The server did not charge the anchor"); return; }
        phase = Phase.CHARGE;
    }

    /** Works out the cover spot. Skips the cover phase when nothing is needed, stops here when nothing would help. */
    private void planCover() {
        Vec3 blast = Vec3.atCenterOf(pos);
        double base = exposure(blast, null);
        if (base <= 0.0) { exposureAfter = 0.0; phase = Phase.EXPLODE; return; }   // already fully shielded by the terrain

        BlockPos best = null;
        double bestExp = base, bestDist = Double.MAX_VALUE;
        Vec3 eye = mc.player.getEyePosition();
        AABB me = mc.player.getBoundingBox();
        boolean haveBlock = glowstoneCount() > 0;                     // the charge used one: is there another one for the cover?
        for (int x = -1; x <= 1 && haveBlock; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            if (x == 0 && y == 0 && z == 0) continue;
            BlockPos c = pos.offset(x, y, z);
            if (!mc.level.getBlockState(c).canBeReplaced()) continue;
            AABB box = new AABB(c);
            if (box.intersects(me)) continue;                          // cannot place a block inside yourself
            if (coverHit(c) == null) continue;                         // nothing valid to click against / out of reach
            double e = exposure(blast, box);
            double d = eye.distanceToSqr(Vec3.atCenterOf(c));
            if (e < bestExp - 1e-9 || (Math.abs(e - bestExp) <= 1e-9 && best != null && d < bestDist)) {
                best = c; bestExp = e; bestDist = d;
            }
        }
        if (best == null) {
            if (requireCover.value) { abort("No spot to cover you: anchor left charged"); return; }   // otherwise detonate anyway
            exposureAfter = base;
            phase = Phase.EXPLODE;
            return;
        }
        coverPos = best;
        exposureAfter = bestExp;
        retries = 0;
        phase = Phase.COVER;
    }

    private void cover() {
        if (!hold(Items.GLOWSTONE)) return;
        if (!mc.level.getBlockState(coverPos).canBeReplaced()) { phase = Phase.COVER_WAIT; return; }
        BlockHitResult hit = coverHit(coverPos);
        if (hit == null) {
            if (requireCover.value) { abort("Cannot reach the cover spot: anchor left charged"); return; }
            phase = Phase.EXPLODE;                                    // no way to place it: detonate anyway
            return;
        }
        if (!aim(hit.getLocation())) return;
        if (!canClick()) return;
        if (click(hit)) { wait = delay.intValue(); phase = Phase.COVER_WAIT; }
    }

    private void coverWait() {
        if (!ServerSync.confirmed(SERVER_TIMEOUT)) return;
        if (mc.level.getBlockState(coverPos).is(Blocks.GLOWSTONE)) { retries = 0; phase = Phase.EXPLODE; return; }
        if (++retries > MAX_RETRIES) {
            if (requireCover.value) { abort("The cover block was not placed: anchor left charged"); return; }
            phase = Phase.EXPLODE;
            return;
        }
        phase = Phase.COVER;
    }

    private void explode() {
        // a hand with glowstone would add charge instead of detonating
        if (mc.player.getMainHandItem().is(Items.GLOWSTONE)) {
            int slot = !mc.player.getInventory().getItem(origSlot).is(Items.GLOWSTONE) ? origSlot
                    : BlockPlacer.findSlot(s -> !s.is(Items.GLOWSTONE));
            if (slot < 0) { abort("No slot to hold instead of glowstone"); return; }
            if (!ServerSync.ready(SERVER_TIMEOUT) || !BlockPlacer.select(slot)) return;
            ServerSync.beginStep();
            swapped = slot != origSlot;
            wait = delay.intValue();
            return;
        }
        BlockHitResult hit = anchorHit();
        if (!aim(hit.getLocation())) return;
        if (!canClick()) return;
        if (!click(hit)) return;
        double self = AutoAnchor.anchorDamage(Vec3.atCenterOf(pos), mc.player) * exposureAfter;
        String msg = String.format("Detonated: ~%.0f damage to you (%.0f%% exposed)", self, exposureAfter * 100);
        if (target != null && target.isAlive()) msg += String.format(", ~%.0f to %s", AutoAnchor.anchorDamage(Vec3.atCenterOf(pos), target), target.getName().getString());
        Notifications.info("Safe Anchor", msg, true);
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

    /** Makes sure the main hand holds the item: false = not yet (a swap was made this tick, or it was refused / missing). */
    private boolean hold(Item item) {
        if (mc.player.getMainHandItem().is(item)) return true;
        int slot = BlockPlacer.findSlot(s -> s.is(item));
        if (slot < 0) { abort("Ran out of " + (item == Items.GLOWSTONE ? "glowstone" : "anchors")); return false; }
        if (!ServerSync.ready(SERVER_TIMEOUT) || !BlockPlacer.select(slot)) return false;
        ServerSync.beginStep();
        swapped = true;
        wait = delay.intValue();
        return false;
    }

    private int glowstoneCount() {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(Items.GLOWSTONE)) n += s.getCount();
        }
        return n;
    }

    /**
     * The click that charges / detonates the anchor: a face that looks at you and that you can see (nothing in the way, so
     * not through the cover block) when there is one. Without silent aim the crosshair's own hit is used when it is on the anchor.
     */
    private BlockHitResult anchorHit() {
        if (!silentAim.value && mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(pos)) return b;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        BlockHitResult best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            Vec3 pt = c.add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
            Vec3 out = eye.subtract(c);
            if (out.x * d.getStepX() + out.y * d.getStepY() + out.z * d.getStepZ() <= 0) continue;   // face turned away from you
            if (!visible(pt, pos)) continue;
            double dist = eye.distanceToSqr(pt);
            if (dist < bestD) { bestD = dist; best = new BlockHitResult(pt, d, pos, false); }
        }
        if (best != null) return best;
        // every face is hidden (the cover is in the way): fall back to the face that looks at you
        Vec3 dd = eye.subtract(c);
        Direction face = Direction.getApproximateNearest(dd.x, dd.y, dd.z);
        Vec3 hit = c.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(hit, face, pos, false);
    }

    /** A click that places a block at c by pressing on a solid neighbour (never the anchor, never a container). Prefers a visible face. */
    private BlockHitResult coverHit(BlockPos c) {
        BlockHitResult h = placementHit(c, pos, true);
        return h != null ? h : placementHit(c, pos, false);
    }

    /**
     * A click that places a block at c by pressing on a solid neighbour. "avoid" is a block never to press on (the anchor).
     * Null if none is in reach (or none is visible when visibleOnly).
     */
    private BlockHitResult placementHit(BlockPos c, BlockPos avoid, boolean visibleOnly) {
        Vec3 eye = mc.player.getEyePosition();
        BlockHitResult best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos s = c.relative(d);
            if (s.equals(avoid)) continue;
            BlockState st = mc.level.getBlockState(s);
            if (st.canBeReplaced() || st.is(Blocks.RESPAWN_ANCHOR) || st.getBlock() instanceof BaseEntityBlock) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(s).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double dist = eye.distanceToSqr(hit);
            if (dist > REACH * REACH || dist >= bestD) continue;
            if (visibleOnly && !visible(hit, s)) continue;
            best = new BlockHitResult(hit, face, s, false);
            bestD = dist;
        }
        return best;
    }

    /** True when a straight line from your eyes to the point reaches it without another block in the way. */
    private boolean visible(Vec3 point, BlockPos block) {
        BlockHitResult r = mc.level.clip(new ClipContext(mc.player.getEyePosition(), point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return r.getType() == HitResult.Type.MISS || r.getBlockPos().equals(block);
    }

    /** The anchor spot (anywhere in your reach) closest to the target's body that you can see and click against, visible faces first. Null if none. */
    private BlockHitResult spotNear(Player t) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 body = t.position().add(0, t.getBbHeight() * 0.5, 0);
        AABB me = mc.player.getBoundingBox(), them = t.getBoundingBox();
        BlockPos base = mc.player.blockPosition();
        BlockHitResult best = null;
        double bestScore = Double.MAX_VALUE;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (int x = -5; x <= 5; x++) for (int y = -5; y <= 5; y++) for (int z = -5; z <= 5; z++) {
                BlockPos c = base.offset(x, y, z);
                if (!mc.level.getBlockState(c).canBeReplaced()) continue;
                AABB box = new AABB(c);
                if (box.intersects(me) || box.intersects(them)) continue;        // would be refused / is inside someone
                Vec3 cc = Vec3.atCenterOf(c);
                if (eye.distanceToSqr(cc) > REACH * REACH) continue;
                BlockHitResult h = placementHit(c, null, pass == 0);
                if (h == null) continue;
                double score = cc.distanceTo(body) - 0.01 * cc.distanceTo(eye);  // closest to them, ties: further from you
                if (score < bestScore) { bestScore = score; best = h; }
            }
        }
        return best;
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

    // ---------------- silent aim ----------------

    /**
     * Turns the server-side aim toward the point, aim-assist style: each tick covers a share of the remaining angle (at least 1 degree,
     * at most "Aim speed"), then a look packet tells the server. True once the aim is within 2 degrees, so the click can go out.
     * Always true with Silent aim off, or when another module already owns the silent rotation.
     */
    private boolean aim(Vec3 point) {
        if (!silentAim.value) return true;
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float wantPitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(h, 1.0E-4))), -90.0, 90.0);
        if (Float.isNaN(curYaw)) { curYaw = mc.player.getYRot(); curPitch = mc.player.getXRot(); }
        curYaw += ease(Mth.wrapDegrees(wantYaw - curYaw), aimSpeed.value);
        curPitch = Mth.clamp(curPitch + ease(wantPitch - curPitch, aimSpeed.value), -90f, 90f);
        if (!Rotations.set(this, curYaw, curPitch)) return true;
        Rotations.sendLookPacket();
        return Math.abs(Mth.wrapDegrees(wantYaw - curYaw)) < 2f && Math.abs(wantPitch - curPitch) < 2f;
    }

    private static float ease(float diff, double maxStep) {
        float a = Math.abs(diff);
        if (a < 0.05f) return diff;
        float step = Math.min(a, (float) Math.min(maxStep, Math.max(a * 0.35f, 1.0f)));
        return Math.copySign(step, diff);
    }

    /**
     * Share (0..1) of sample points over your body that the blast at "from" can reach in a straight line, the way the game
     * scales explosion damage. "extra" is a hypothetical block (the cover) that is also in the way. The anchor itself is
     * ignored because it is removed before the explosion.
     */
    private double exposure(Vec3 from, AABB extra) {
        AABB b = mc.player.getBoundingBox();
        int seen = 0, total = 0;
        for (int x = 0; x <= 2; x++) for (int y = 0; y <= 3; y++) for (int z = 0; z <= 2; z++) {
            Vec3 pt = new Vec3(b.minX + b.getXsize() * (x / 2.0), b.minY + b.getYsize() * (y / 3.0), b.minZ + b.getZsize() * (z / 2.0));
            total++;
            if (clear(from, pt, extra)) seen++;
        }
        return seen / (double) total;
    }

    private boolean clear(Vec3 a, Vec3 b, AABB extra) {
        if (extra != null && extra.clip(a, b).isPresent()) return false;
        Vec3 d = b.subtract(a);
        int steps = Math.max(2, (int) Math.ceil(d.length() / 0.1));
        for (int i = 1; i < steps; i++) {
            Vec3 p = a.add(d.scale(i / (double) steps));
            BlockPos bp = BlockPos.containing(p);
            if (bp.equals(pos)) continue;
            VoxelShape shape = mc.level.getBlockState(bp).getCollisionShape(mc.level, bp);
            if (shape.isEmpty()) continue;
            for (AABB bb : shape.toAabbs()) if (bb.move(bp).contains(p)) return false;
        }
        return true;
    }

    /** Same conditions the vanilla right-click needs: not mid item use, not sneaking (sneaking skips block interaction). */
    private boolean canClick() {
        return !mc.player.isUsingItem() && !mc.player.isSecondaryUseActive() && ServerSync.ready(SERVER_TIMEOUT);
    }

    /** Vanilla use: MultiPlayerGameMode.useItemOn (sends the use packet with its sequence number), swing if it was consumed. */
    private boolean click(BlockHitResult hit) {
        if (!mc.level.getWorldBorder().isWithinBounds(hit.getBlockPos())) return false;
        InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        if (!r.consumesAction()) return false;
        mc.player.swing(InteractionHand.MAIN_HAND);
        ServerSync.beginStep();
        return true;
    }

    private boolean isAnchor() { return pos != null && mc.level.getBlockState(pos).is(Blocks.RESPAWN_ANCHOR); }

    private int chargeLevel() {
        BlockState st = mc.level.getBlockState(pos);
        return st.is(Blocks.RESPAWN_ANCHOR) ? st.getValue(RespawnAnchorBlock.CHARGE) : -1;
    }

    /** Nothing was done yet: report and switch off. */
    private void fail(String why) {
        Notifications.info("Safe Anchor", why, false);
        finish();
    }

    /** Stop part-way: put the slot back, then switch off. */
    private void abort(String why) {
        Notifications.info("Safe Anchor", why, false);
        if (swapped) phase = Phase.RESTORE; else finish();
    }

    private void finish() {
        Rotations.release(this);
        curYaw = Float.NaN; curPitch = Float.NaN;
        phase = Phase.IDLE;
        if (isEnabled()) setEnabled(false);
    }
}
