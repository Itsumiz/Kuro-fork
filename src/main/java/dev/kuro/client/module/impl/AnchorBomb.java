package dev.kuro.client.module.impl;

import dev.kuro.client.KuroClient;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.ServerSync;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Place a respawn anchor yourself and this fills it with glowstone and detonates it, the way a player would, with nothing
 * extra on the wire:
 *   place (you)  ->  select glowstone  ->  right-click the anchor  ->  select your old slot  ->  right-click the anchor.
 *  - It only reacts to anchors YOU placed (hook: ClientPlayerInteractionManagerMixin -> beforeUse / afterUse).
 *  - Every right-click is the game's own MultiPlayerGameMode.useItemOn with the hit result your crosshair really has, so the
 *    packet carries a real face and hit point. No rotation or look packet is sent: it waits until your crosshair is on the anchor.
 *  - One interaction step per tick, and each next step waits for the server to answer the previous one (ServerSync), the same
 *    pacing as the other modules. Slot changes go through the normal selected-slot path (the game sends that packet itself).
 *  - Does nothing in the Nether (anchors do not explode there) and while Auto Anchor is on (that one runs its own sequence).
 *  - The glowstone has to be in your hotbar. One glowstone is enough: the blast does not depend on the charge level.
 */
public class AnchorBomb extends Module {
    private static final int MAX_AGE = 100;          // ticks before an unfinished sequence is given up
    private static final int SERVER_TIMEOUT = 5;     // ticks to wait for a server transaction before moving on

    private static AnchorBomb instance;
    private static BlockPos pendingTarget;
    private static boolean preAnchor;

    private final Setting.Bool explode = add(new Setting.Bool("Detonate", true));
    private final Setting.Bool limitSelf = add(new Setting.Bool("Limit self damage", false));   // off by default: no self damage cap
    private final Setting.Num maxSelf = add(new Setting.Num("Max self damage", 10.0, 0.0, 36.0, 0.5));   // only used when "Limit self damage" is on
    private final Setting.Num delay = add(new Setting.Num("Extra delay (ticks)", 0, 0, 10, 1));

    private enum Phase { IDLE, GLOW_SELECT, CHARGE_CLICK, CHARGED_WAIT, EXPLODE_SELECT, EXPLODE_CLICK, RESTORE }
    private Phase phase = Phase.IDLE;
    private BlockPos pos;
    private int origSlot, age, wait;
    private boolean swapped, ownClick;

    public AnchorBomb() { super("Anchor Bomb", Category.COMBAT); instance = this; }

    @Override
    protected void onDisable() {
        if (swapped && mc.player != null) BlockPlacer.selectForce(origSlot);
        finish();
    }

    // ---------------- hooks (called by ClientPlayerInteractionManagerMixin around every block use) ----------------

    public static void beforeUse(BlockHitResult hit) {
        pendingTarget = null;
        AnchorBomb m = instance;
        if (m == null || !m.isEnabled() || m.ownClick || mc.level == null) return;
        BlockPos clicked = hit.getBlockPos();
        // like BlockPlaceContext: a replaceable block (grass...) is replaced, anything else is built against
        pendingTarget = mc.level.getBlockState(clicked).canBeReplaced() ? clicked : clicked.relative(hit.getDirection());
        preAnchor = mc.level.getBlockState(pendingTarget).is(Blocks.RESPAWN_ANCHOR);
    }

    public static void afterUse(BlockHitResult hit, InteractionResult result) {
        AnchorBomb m = instance;
        BlockPos target = pendingTarget;
        pendingTarget = null;
        if (m == null || target == null || !m.isEnabled() || m.ownClick || m.phase != Phase.IDLE || mc.level == null) return;
        if (SafeAnchor.running()) return;                           // Safe Anchor runs its own sequence
        if (preAnchor || !result.consumesAction() || mc.level.dimension() == Level.NETHER) return;
        for (Module o : KuroClient.modules.modules) if (o instanceof AutoAnchor && o.isEnabled()) return;
        BlockState st = mc.level.getBlockState(target);
        if (st.is(Blocks.RESPAWN_ANCHOR) && st.getValue(RespawnAnchorBlock.CHARGE) == 0) m.start(target);
    }

    private void start(BlockPos target) {
        pos = target;
        origSlot = BlockPlacer.selected();
        age = 0;
        wait = delay.intValue();
        swapped = false;
        phase = Phase.GLOW_SELECT;
    }

    // ---------------- the sequence (start of the tick, where a player's key presses are handled) ----------------

    @Override
    public void onPreTick() {
        if (phase == Phase.IDLE) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) { finish(); return; }
        if (mc.screen != null) return;                              // a menu is open: wait, like a player would
        if (++age > MAX_AGE) { abort("Gave up (took too long)"); return; }
        if (wait > 0) { wait--; return; }
        if (phase != Phase.RESTORE && phase != Phase.EXPLODE_CLICK && !isAnchor()) { abort("The anchor is gone"); return; }

        switch (phase) {
            case GLOW_SELECT -> glowSelect();
            case CHARGE_CLICK -> chargeClick();
            case CHARGED_WAIT -> chargedWait();
            case EXPLODE_SELECT -> explodeSelect();
            case EXPLODE_CLICK -> explodeClick();
            case RESTORE -> restore();
            default -> { }
        }
    }

    private void glowSelect() {
        if (mc.player.getMainHandItem().is(Items.GLOWSTONE)) { phase = Phase.CHARGE_CLICK; chargeClick(); return; }
        int slot = BlockPlacer.findSlot(s -> s.is(Items.GLOWSTONE));
        if (slot < 0) { abort("No glowstone in your hotbar"); return; }
        if (!ServerSync.ready(SERVER_TIMEOUT) || !BlockPlacer.select(slot)) return;   // refused this tick: try again next tick
        ServerSync.beginStep();
        swapped = true;
        wait = delay.intValue();
        phase = Phase.CHARGE_CLICK;
    }

    private void chargeClick() {
        if (!mc.player.getMainHandItem().is(Items.GLOWSTONE)) { phase = Phase.GLOW_SELECT; return; }
        BlockHitResult hit = lookingAtAnchor();
        if (hit == null || !canClick()) return;                      // crosshair is elsewhere / still sneaking: wait
        if (click(hit)) { wait = delay.intValue(); phase = Phase.CHARGED_WAIT; }
    }

    private void chargedWait() {
        if (!ServerSync.confirmed(SERVER_TIMEOUT)) return;           // wait for the server to answer the charge click
        int charge = charge();
        if (charge > 0) phase = explode.value ? Phase.EXPLODE_SELECT : Phase.RESTORE;
        else phase = Phase.CHARGE_CLICK;                             // the server did not take it: click again
    }

    private void explodeSelect() {
        if (!mc.player.getMainHandItem().is(Items.GLOWSTONE)) { phase = Phase.EXPLODE_CLICK; explodeClick(); return; }
        // a hand with glowstone would add charge instead of detonating, so go back to the slot we came from
        int slot = !mc.player.getInventory().getItem(origSlot).is(Items.GLOWSTONE) ? origSlot
                : BlockPlacer.findSlot(s -> !s.is(Items.GLOWSTONE));
        if (slot < 0) { abort("No slot to hold instead of glowstone"); return; }
        if (!ServerSync.ready(SERVER_TIMEOUT) || !BlockPlacer.select(slot)) return;
        ServerSync.beginStep();
        swapped = slot != origSlot;
        wait = delay.intValue();
        phase = Phase.EXPLODE_CLICK;
    }

    private void explodeClick() {
        if (mc.player.getMainHandItem().is(Items.GLOWSTONE)) { phase = Phase.EXPLODE_SELECT; return; }
        if (!isAnchor()) { phase = Phase.RESTORE; return; }
        BlockHitResult hit = lookingAtAnchor();
        if (hit == null || !canClick()) return;
        double self = AutoAnchor.anchorDamage(Vec3.atCenterOf(pos), mc.player);
        if (limitSelf.value && self > maxSelf.value) {
            Notifications.info("Anchor Bomb", String.format("Left charged: ~%.0f damage to you", self), false);
            phase = Phase.RESTORE;
            return;
        }
        if (click(hit)) phase = Phase.RESTORE;
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

    /** The crosshair's own block hit, when it is on our anchor. This is exactly what a real right-click would send. */
    private BlockHitResult lookingAtAnchor() {
        return mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(pos) ? b : null;
    }

    /** Same conditions the vanilla right-click needs: not mid item use, not sneaking (sneaking skips block interaction). */
    private boolean canClick() {
        return !mc.player.isUsingItem() && !mc.player.isSecondaryUseActive() && ServerSync.ready(SERVER_TIMEOUT);
    }

    /** Vanilla use: MultiPlayerGameMode.useItemOn (sends the use packet with its sequence number), swing if it was consumed. */
    private boolean click(BlockHitResult hit) {
        if (!mc.level.getWorldBorder().isWithinBounds(hit.getBlockPos())) return false;
        ownClick = true;
        try {
            InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            if (!r.consumesAction()) return false;
            mc.player.swing(InteractionHand.MAIN_HAND);
            ServerSync.beginStep();
            return true;
        } finally {
            ownClick = false;
        }
    }

    private boolean isAnchor() { return pos != null && mc.level.getBlockState(pos).is(Blocks.RESPAWN_ANCHOR); }

    private int charge() {
        BlockState st = mc.level.getBlockState(pos);
        return st.is(Blocks.RESPAWN_ANCHOR) ? st.getValue(RespawnAnchorBlock.CHARGE) : -1;
    }

    private void abort(String why) {
        Notifications.info("Anchor Bomb", why, false);
        phase = swapped ? Phase.RESTORE : Phase.IDLE;
        if (phase == Phase.IDLE) finish();
    }

    private void finish() {
        phase = Phase.IDLE;
        pos = null;
        swapped = false;
    }
}
