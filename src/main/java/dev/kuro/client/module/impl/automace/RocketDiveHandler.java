package dev.kuro.client.module.impl.automace;

import dev.kuro.client.module.impl.AutoMace;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.ForcedKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Rocket / elytra dive, done only with things a player can do in the game:
 *  - the elytra and the chestplate are swapped by right clicking them in the hotbar (that is what equipping armour from
 *    the hand does), never by inventory click packets;
 *  - gliding starts with a real jump press (the jump key is held for a moment, the game sends the glide start itself);
 *  - fireworks are used like any item, only while gliding;
 *  - aiming turns the real player rotation (AutoMace.aimAt), so the server only gets it in the normal movement packets;
 *  - slot changes go through BlockPlacer.select (one swap per tick), attacks through AutoMace.executeAttack (one per tick,
 *    in reach, crosshair on the target).
 * A step that cannot be done this tick simply waits for the next one. The dive only starts when every item it needs is
 * in the hotbar, so it can never end up half way through with something missing.
 */
public final class RocketDiveHandler {
    private static final Minecraft mc = Minecraft.getInstance();

    private enum State { IDLE, START, ASCEND, DROP, ATTACK, RECOVER }

    private static final long MAX_RUN_MS = 25_000L;     // a dive that has not finished by then is abandoned
    private static final long MAX_STEP_MS = 4_000L;     // so is any single step that keeps failing

    private final AutoMace parent;
    private State state = State.IDLE;
    private Entity target;
    private int originalSlot = -1;
    private boolean startedWithElytra;
    private long nextAt, runStart, stepStart, lastFirework, lastAttack, dropAt;
    private double fallStartY = Double.NaN;

    // jump key tap (a press that the game reads as a new key press)
    private boolean jumpForced;
    private long jumpReleaseAt, lastJumpReleased;

    public RocketDiveHandler(AutoMace parent) { this.parent = parent; }

    public boolean idle() { return state == State.IDLE; }

    // ------------------------------------------------------------------ lifecycle

    /** Ends the dive. With reequip the gear is put back the way the dive found it (hotbar right click, never a click packet). */
    public void reset(boolean restore) {
        boolean wasRunning = state != State.IDLE;
        releaseJump();
        if (wasRunning && restore && mc.player != null && mc.gameMode != null) restoreGear();
        state = State.IDLE;
        target = null;
        originalSlot = -1;
        fallStartY = Double.NaN;
        parent.releaseAim();
    }

    private void restoreGear() {
        // Clean-up path (module switched off / dive abandoned): put back what we wore at the start.
        boolean wantElytra = startedWithElytra;
        if (hasElytraEquipped() != wantElytra) {
            int slot = BlockPlacer.findSlot(wantElytra ? s -> s.is(Items.ELYTRA) : RocketDiveHandler::isChestplate);
            if (slot >= 0) {
                BlockPlacer.selectForce(slot);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            }
        }
        if (originalSlot >= 0) BlockPlacer.selectForce(originalSlot);
    }

    public void tick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) { reset(false); return; }
        long now = System.currentTimeMillis();

        if (jumpForced && now >= jumpReleaseAt) releaseJump();
        if (state == State.IDLE) { if (now >= nextAt) beginIfReady(now); return; }

        // sanity: things that make the current plan impossible end the dive instead of leaving it stuck
        if (mc.player.isPassenger() || mc.player.isInWater() || mc.player.isSpectator()
                || now - runStart > MAX_RUN_MS || (state != State.ASCEND && now - stepStart > MAX_STEP_MS)) {
            reset(true);
            nextAt = now + 1000;
            return;
        }
        if (now < nextAt) return;

        switch (state) {
            case START -> doStart(now);
            case ASCEND -> doAscend(now);
            case DROP -> doDrop(now);
            case ATTACK -> doAttack(now);
            case RECOVER -> doRecover(now);
            default -> {}
        }
    }

    private void enter(State s, long now, long delayMs) {
        state = s;
        stepStart = now;
        nextAt = now + delayMs;
    }

    // ------------------------------------------------------------------ steps

    private void beginIfReady(long now) {
        if (mc.screen != null || mc.player.isPassenger() || mc.player.isInWater()) return;
        if (!mc.player.onGround() && !mc.player.isFallFlying()) return;   // only from the ground or while already gliding
        target = AutoMaceTargeting.find(
                parent.rocketTargetRange(), parent.targetPlayers(), parent.targetMobs(),
                AutoMaceTargeting.Priority.DISTANCE, parent.rocketSnapFov());
        if (target == null) return;

        // Everything the whole dive needs must already be in the hotbar, or it is not started at all.
        boolean elytraOn = hasElytraEquipped();
        boolean elytraInBar = BlockPlacer.findSlot(s -> s.is(Items.ELYTRA)) >= 0;
        boolean plateInBar = BlockPlacer.findSlot(RocketDiveHandler::isChestplate) >= 0;
        boolean plateWorn = isChestplate(mc.player.getItemBySlot(EquipmentSlot.CHEST));
        boolean canHaveElytra = elytraOn || elytraInBar;
        boolean canDrop = elytraOn ? plateInBar : plateWorn || plateInBar;   // taking the elytra off needs a chestplate to put on
        if (!canHaveElytra || !canDrop) { target = null; return; }
        if (BlockPlacer.findSlot(s -> s.is(Items.FIREWORK_ROCKET)) < 0) { target = null; return; }
        if (parent.findMaceSlot(0) < 0) { target = null; return; }

        startedWithElytra = elytraOn;
        originalSlot = BlockPlacer.selected();
        runStart = now;
        fallStartY = Double.NaN;
        enter(State.START, now, 0);
    }

    /** Gets the player gliding: elytra on, a jump to leave the ground, a second jump press in the air. */
    private void doStart(long now) {
        if (invalidTarget()) { reset(true); return; }
        if (mc.player.isFallFlying()) { enter(State.ASCEND, now, 0); return; }

        if (!hasElytraEquipped()) { equipChest(true, now); return; }

        if (mc.player.onGround()) { tapJump(now); return; }
        // in the air with the elytra on and falling: the next jump press starts the glide
        if (!jumpForced && now - lastJumpReleased > 80 && mc.player.getDeltaMovement().y < 0) tapJump(now);
    }

    private void doAscend(long now) {
        if (invalidTarget()) { reset(true); return; }
        if (!mc.player.isFallFlying()) {   // lost the glide: start it again
            if (mc.player.onGround() || hasElytraEquipped()) { enter(State.START, now, 0); return; }
        }
        Vec3 above = new Vec3(target.getX(), target.getY() + parent.rocketHeightAbove(), target.getZ());
        Vec3 lead = above.add(target.getLookAngle().scale(parent.rocketAimOffset()));
        parent.aimAt(AutoMaceTargeting.angles(lead), mc.player.distanceTo(target));

        double wantY = target.getY() + parent.rocketHeightAbove();
        double horiz = Math.hypot(target.getX() - mc.player.getX(), target.getZ() - mc.player.getZ());
        if (mc.player.getY() >= wantY - 1.5 && horiz <= 8.0) {
            enter(State.DROP, now, parent.rocketDropDelayMs());
            return;
        }
        if (now - lastFirework >= parent.rocketFireworkDelayMs() && mc.player.isFallFlying()) {
            int fw = BlockPlacer.findSlot(s -> s.is(Items.FIREWORK_ROCKET));
            if (fw < 0) { reset(true); return; }
            if (!BlockPlacer.select(fw)) return;   // swap refused: next tick
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            lastFirework = now;
        }
    }

    /** Takes the elytra off by right clicking a chestplate: gliding stops and the fall (and the smash bonus) begins. */
    private void doDrop(long now) {
        if (invalidTarget()) { reset(true); return; }
        if (hasElytraEquipped()) { equipChest(false, now); return; }
        fallStartY = mc.player.getY();
        dropAt = now;
        enter(State.ATTACK, now, 0);
    }

    private void doAttack(long now) {
        if (invalidTarget()) { enter(State.RECOVER, now, 0); return; }
        if (Double.isNaN(fallStartY)) fallStartY = mc.player.getY();
        double fall = Math.max(0, fallStartY - mc.player.getY());

        parent.aimAt(AutoMaceTargeting.angles(AutoMaceTargeting.aimPoint(target, AutoMaceTargeting.AimPoint.CENTER)), mc.player.distanceTo(target));

        if (mc.player.onGround()) { enter(State.RECOVER, now, 0); return; }   // landed without a hit
        if (now - dropAt < parent.rocketAttackDelayMs() || now - lastAttack < parent.rocketAttackDelayMs()) return;
        if (fall < parent.minFall()) return;
        if (mc.player.getDeltaMovement().y > -0.1) return;                         // a smash needs a real fall

        int mace = parent.findMaceSlot(fall);
        if (mace < 0) { enter(State.RECOVER, now, 0); return; }
        if (!BlockPlacer.select(mace)) return;

        // inside "Rocket attack range" and under the crosshair, charged: AutoMace.executeAttack checks all of it
        if (mc.player.distanceTo(target) > parent.rocketAttackRange() + 1.5) return;   // still closing in
        if (parent.executeAttack(target)) {
            lastAttack = now;
            enter(State.RECOVER, now, parent.rocketReequipDelayMs());
        }
    }

    private void doRecover(long now) {
        boolean wantElytra = startedWithElytra || parent.rocketRecover();
        if (hasElytraEquipped() != wantElytra) { equipChest(wantElytra, now); return; }
        if (originalSlot >= 0 && !BlockPlacer.select(originalSlot)) return;

        Entity next = parent.rocketLoop() && parent.rocketRecover() && target != null && target.isAlive()
                ? AutoMaceTargeting.find(parent.rocketTargetRange(), parent.targetPlayers(), parent.targetMobs(),
                        AutoMaceTargeting.Priority.DISTANCE, parent.rocketSnapFov())
                : null;
        long glideDelay = parent.rocketGlideDelayMs();
        releaseJump();
        parent.releaseAim();
        if (next != null) {
            target = next;
            fallStartY = Double.NaN;
            runStart = now;   // a loop is several dives, each gets its own time budget
            enter(State.START, now, glideDelay);
        } else {
            state = State.IDLE;
            target = null;
            originalSlot = -1;
            fallStartY = Double.NaN;
            nextAt = now + 500;
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean invalidTarget() {
        return target == null || !target.isAlive() || target.isRemoved()
                || mc.player.distanceTo(target) > parent.rocketTargetRange() * 1.5;
    }

    /**
     * Wears the elytra (true) or the chestplate (false) by selecting it in the hotbar and right clicking, one swap per
     * tick. The result is checked on the next call; the Max step time ends a dive that keeps failing.
     */
    private void equipChest(boolean elytra, long now) {
        int slot = BlockPlacer.findSlot(elytra ? s -> s.is(Items.ELYTRA) : RocketDiveHandler::isChestplate);
        if (slot < 0) { reset(false); return; }   // the item is not in the hotbar any more: give up, nothing half-done
        if (!BlockPlacer.select(slot)) return;
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        nextAt = now + 100;   // let the swap settle before looking again
    }

    private void tapJump(long now) {
        if (jumpForced) return;
        ForcedKeys.force(mc.options.keyJump);
        jumpForced = true;
        jumpReleaseAt = now + 60;   // held across a full tick, then released so the next press counts as new
    }

    private void releaseJump() {
        if (!jumpForced) return;
        ForcedKeys.release(mc.options.keyJump);
        jumpForced = false;
        lastJumpReleased = System.currentTimeMillis();
    }

    private boolean hasElytraEquipped() {
        return mc.player != null && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private static boolean isChestplate(ItemStack s) {
        return !s.isEmpty() && !s.is(Items.ELYTRA) && s.getItem().toString().toLowerCase(java.util.Locale.ROOT).contains("chestplate");
    }
}
