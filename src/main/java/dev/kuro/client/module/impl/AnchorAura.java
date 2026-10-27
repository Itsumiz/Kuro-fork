package dev.kuro.client.module.impl;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kuro.client.echo.anchor.AnchorAimSearch;
import dev.kuro.client.echo.event.EventMove;
import dev.kuro.client.echo.event.EventStartUseItem;
import dev.kuro.client.echo.event.EventSubscribe;
import dev.kuro.client.echo.event.MouseUpdateEvent;
import dev.kuro.client.echo.handlers.InputHandler;
import dev.kuro.client.echo.handlers.RotationHandler;
import dev.kuro.client.echo.handlers.SwapStateManager;
import dev.kuro.client.echo.utils.blocks.BlockUtils;
import dev.kuro.client.echo.utils.combat.ExplosionUtils;
import dev.kuro.client.echo.utils.combat.TargetUtils;
import dev.kuro.client.echo.utils.inventory.InventoryUtils;
import dev.kuro.client.echo.utils.rotation.RotationConvergenceTracker;
import dev.kuro.client.echo.utils.rotation.RotationUtils;
import dev.kuro.client.event.EventHandler;
import dev.kuro.client.mixin.EchoKeyMappingAccessor;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.DebugChat;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Anchor Aura: port of the echo client's "Auto Anchor" (features/impl/combat/AutoAnchor.java) with its helper classes
 * (dev.kuro.client.echo.*). Kuro already has its own "Auto Anchor", so this one is registered as "Anchor Aura".
 *
 * Picks an anchor placement near the closest target, places it, and runs the charge/explode sequence. Optional safety-block
 * step drops a glowstone between us and the anchor before charging so we eat less of our own explosion.
 *
 * <ul>
 *   <li>{@link AnchorAimSearch} finds an aim point on the anchor that raycasts cleanly (works even when an adjacent block
 *       occludes the eye-side face).</li>
 *   <li>{@link RotationConvergenceTracker} tracks the per-tick rotation delta so interactions only fire on a tick where
 *       Grim's {@code deltaX > 2} gate is closed.</li>
 * </ul>
 *
 * Settings differences from the echo client (Kuro's menu has no keybind / range / mode / colour-with-alpha widgets):
 *  - Mode is a 2-step selector, Activate Key is a number plus a text box (type e.g. R, F4, MOUSE4, none, then Enter),
 *  - every Range is a "min" and a "max" slider, every colour is a colour wheel plus an alpha slider,
 *  - settings are always visible (no show-only-when dependencies).
 */
public class AnchorAura extends Module {

    private static final int VANILLA_PLACE_COOLDOWN_TICKS = 4;
    private static final int MAX_ANCHOR_CHARGES = 4;
    private static final float ROTATION_SETTLE_THRESHOLD = 1.7f;

    private enum AnchorStep { PLACE_SAFETY, CHARGE, EXPLODE }

    public AnchorAura() {
        super("Anchor Aura", Category.COMBAT);
    }

    // -- Settings --

    private final Setting.Num mode = add(new Setting.Num("Mode", 0, 0, 1, 1).labelled("Click", "Hold"));
    /** -1 = none, 0..399 = keyboard (GLFW code), 1000 + n = mouse button n (1000 = left, 1001 = right, 1002 = middle, 1003 = MOUSE4 ...). */
    private final Setting.Num activateKeyCode = add(new Setting.Num("Activate Key (code)", -1, -1, 1007, 1));
    private final Setting.Text activateKeyName = add(new Setting.Text("Activate Key: type a key, Enter", this::bindKeyByName));
    private final Setting.Bool repeat = add(new Setting.Bool("Repeat", true));
    private final Setting.Bool autoAim = add(new Setting.Bool("Auto Aim", true));
    private final Setting.Bool silentAim = add(new Setting.Bool("Silent Aim", false));
    private final Setting.Num aimSpeed = add(new Setting.Num("Aim Speed", 120, 0, 600, 1));
    private final Setting.Bool renderPosition = add(new Setting.Bool("Render Position", true));
    private final Setting.Hue fillColor = add(new Setting.Hue("Fill Color", 0.583f).wheel(1f, 1f));
    private final Setting.Num fillAlpha = add(new Setting.Num("Fill Alpha", 40, 0, 255, 1));
    private final Setting.Hue outlineColor = add(new Setting.Hue("Outline Color", 0f).wheel(0f, 1f));
    private final Setting.Num outlineAlpha = add(new Setting.Num("Outline Alpha", 255, 0, 255, 1));
    private final Setting.Bool canPlaceLegit = add(new Setting.Bool("Can Place Legit", true));   // positions where you can only place legit
    private final Setting.Bool safeAnchor = add(new Setting.Bool("Safe Anchor", false));          // safety block at your feet before charging
    private final Setting.Num safetyTimeout = add(new Setting.Num("Safety Timeout (ms)", 200, 0, 2000, 10));
    private final Setting.Num minDamage = add(new Setting.Num("Min Damage", 6.0, 0.0, 20.0, 0.5));
    private final Range charges = new Range("Charges", 1, 1, 1, MAX_ANCHOR_CHARGES, 1);
    private final Range chargeDelay = new Range("Charge Delay (ticks)", 1, 1, 0, 20, 1);
    private final Setting.Num explodeSlot = add(new Setting.Num("Explode Slot", 1, 1, 9, 1));
    private final Range explodeDelay = new Range("Explode Delay (ticks)", 1, 1, 0, 20, 1);

    // -- State --

    private boolean clickQueued;
    private boolean keyWasDown;
    private boolean wasAiming;
    private boolean restoringSwap;
    private boolean holdCycleCompleted;
    private int placeCooldown;
    private AnchorStep anchorStep;
    private BlockPos activeAnchorPos;
    private BlockPos pendingUseKeyAnchorPos;
    private int remainingCharges;
    private int nextAnchorActionTick;

    /** Aim point picked once per sequence; revalidated each call so a freshly
     *  placed safety block can force a re-pick onto an unobstructed face. */
    private Vec3 cachedAnchorAim;

    /** Wall-clock at PLACE_SAFETY entry; used by {@link #safetyTimeout}. */
    private long safetyStepStartMillis;

    private final RotationConvergenceTracker rotation = new RotationConvergenceTracker();

    // -- Kuro setting glue (replaces ModeSetting / KeybindSetting / RangeSetting of the echo client) --

    /** RangeSetting: a min and a max slider, getRandom() = uniform value between them. */
    private final class Range {
        final Setting.Num min, max;

        Range(String name, float defMin, float defMax, float lower, float upper, float step) {
            min = add(new Setting.Num(name + " min", defMin, lower, upper, step));
            max = add(new Setting.Num(name + " max", defMax, lower, upper, step));
        }

        float getRandom() {
            float lo = (float) Math.min(min.value, max.value);
            float hi = (float) Math.max(min.value, max.value);
            return (float) (Math.random() * (hi - lo)) + lo;
        }
    }

    private boolean isHold() {
        return mode.intValue() == 1;
    }

    private boolean isNull() {
        return mc.player == null || mc.level == null;
    }

    /** The activate key in InputHandler format: keyboard = GLFW code, mouse = 0x80000000 | button, -1 = none. */
    private int activateBind() {
        int code = activateKeyCode.intValue();
        if (code < 0) return -1;
        if (code >= 1000) return 0x80000000 | (code - 1000);
        return code;
    }

    private void bindKeyByName(String raw) {
        String name = raw.trim();
        String up = name.toUpperCase(Locale.ROOT);
        if (up.equals("NONE") || up.equals("-") || up.equals("OFF")) {
            activateKeyCode.value = -1;
            DebugChat.say("Anchor Aura", "Activate key cleared");
            return;
        }
        try {
            if (up.startsWith("MOUSE")) {
                int n = Integer.parseInt(up.substring(5));   // MOUSE1 = left, MOUSE2 = right, MOUSE3 = middle, MOUSE4, MOUSE5 ...
                if (n < 1 || n > 8) throw new IllegalArgumentException();
                activateKeyCode.value = 1000 + (n - 1);
            } else {
                InputConstants.Key key = InputConstants.getKey("key.keyboard." + name.toLowerCase(Locale.ROOT).replace(' ', '.'));
                int value = key.getValue();
                if (value < 0 || value > 399) throw new IllegalArgumentException();
                activateKeyCode.value = value;
            }
            DebugChat.say("Anchor Aura", "Activate key set to " + up);
        } catch (RuntimeException e) {
            DebugChat.say("Anchor Aura", "Unknown key \"" + name + "\" (try R, F4, SPACE, LSHIFT, MOUSE4 or none)");
        }
    }

    // -- Lifecycle --

    @Override
    protected void onEnable() {
        super.onEnable();
        clickQueued = false;
        keyWasDown = false;
        wasAiming = false;
        restoringSwap = false;
        holdCycleCompleted = false;
        placeCooldown = 0;
        rotation.reset();
        resetAnchorSequence();
    }

    @Override
    protected void onDisable() {
        clickQueued = false;
        keyWasDown = false;
        wasAiming = false;
        restoringSwap = false;
        holdCycleCompleted = false;
        placeCooldown = 0;
        resetAnchorSequence();
        SwapStateManager.cancel(this, true);
        if (RotationUtils.isControlledBy(this)) {
            RotationUtils.stopTracking();
        }
        super.onDisable();
    }

    // -- Event handlers --

    @SuppressWarnings("unused")
    @EventHandler
    private void onStartUseItem(EventStartUseItem.Pre event) {
        if (!shouldHandleSameUseKey()) return;
        if (event.getPlayer() != mc.player) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (mc.screen != null) return;
        if (isHold() && !InputHandler.isBindDown(activateBind()) && !isAnchorSequenceActive()) return;

        boolean startFromAnchor = !isAnchorSequenceActive() && event.getStack().is(Items.RESPAWN_ANCHOR);
        if (startFromAnchor) {
            pendingUseKeyAnchorPos = anchorPosOf(event.getHitResult());
        }

        if (!startFromAnchor && !isAnchorSequenceActive() && !SwapStateManager.isOwnerActive(this) && !restoringSwap) {
            return;
        }

        if (!isHold() && startFromAnchor) {
            clickQueued = true;
            if (!isAnchorSequenceActive()) {
                restoringSwap = false;
                placeCooldown = 0;
            }
        }

        event.cancel();
    }

    /** Draws the predicted placement (Kuro draws world boxes in onWorld instead of an EventRender3D framebuffer). */
    @Override
    public void onWorld() {
        if (isNull() || !renderPosition.value) return;

        boolean renderFill = fillAlpha.intValue() > 0;
        boolean renderOutline = outlineAlpha.intValue() > 0;
        if (!renderFill && !renderOutline) return;

        BlockUtils.PlacementHit bestHit = findBestPlacementHit(partialTick());
        if (bestHit == null) return;

        BlockPos bestPos = bestHit.placementPos();
        int line = renderOutline ? outlineColor.argb(outlineAlpha.intValue()) : 0;
        int fill = renderFill ? fillColor.argb(fillAlpha.intValue()) : 0;
        WorldGfx.box(bestPos.getX(), bestPos.getY(), bestPos.getZ(),
                bestPos.getX() + 1.0, bestPos.getY() + 1.0, bestPos.getZ() + 1.0, line, fill, 2, false);
    }

    @Override
    public void onPreTick() {
        rotation.update();

        if (placeCooldown > 0) placeCooldown--;
        if (!SwapStateManager.isOwnerActive(this)) restoringSwap = false;

        if (isNull() || mc.screen != null) {
            clickQueued = false;
            resetAnchorSequence();
            handleInactive();
            keyWasDown = false;
            return;
        }

        boolean sameUseKey = shouldHandleSameUseKey();
        boolean keyDown = InputHandler.isBindDown(activateBind());
        boolean holdMode = isHold();
        if (holdMode && !keyDown) holdCycleCompleted = false;

        if (!holdMode && keyDown && !keyWasDown && (!sameUseKey || isHoldingRespawnAnchor())) {
            clickQueued = true;
            if (!isAnchorSequenceActive()) {
                restoringSwap = false;
                placeCooldown = 0;
            }
        }

        boolean active = holdMode ? keyDown : clickQueued || isAnchorSequenceActive();
        if (!active) {
            resetAnchorSequence();
            handleInactive();
            keyWasDown = keyDown;
            return;
        }

        if (holdMode && !repeat.value && holdCycleCompleted && !isAnchorSequenceActive()) {
            handleInactive();
            keyWasDown = keyDown;
            return;
        }

        if (sameUseKey && !isAnchorSequenceActive() && !isHoldingRespawnAnchor()) {
            if (!holdMode) clickQueued = false;
            if (!SwapStateManager.isOwnerActive(this)) handleInactive();
            keyWasDown = keyDown;
            return;
        }

        if (isAnchorSequenceActive()) {
            continueAnchorSequence();
            keyWasDown = keyDown;
            return;
        }

        BlockPos existingAnchor = consumePendingUseKeyAnchorPos();
        if (existingAnchor == null) {
            BlockHitResult hit = currentBlockHit();
            if (hit != null && isRespawnAnchor(hit.getBlockPos())) existingAnchor = hit.getBlockPos();
        }

        if (existingAnchor != null) {
            if (!isAnchorWithinInteractionRange(existingAnchor)) {
                if (!holdMode) clickQueued = false;
                handleInactive();
                keyWasDown = keyDown;
                return;
            }
            beginAnchorSequence(existingAnchor, rollChargeCount());
            keyWasDown = keyDown;
            return;
        }

        keyWasDown = keyDown;
        runFreshAnchorPlacement(holdMode, sameUseKey, keyDown);
    }

    /** Handles the "no active sequence yet, no anchor under cursor" branch -- find a spot, swap, place. */
    private void runFreshAnchorPlacement(boolean holdMode, boolean useHeldAnchorSlot, boolean keyDown) {
        // Same-use-key flow: the user drives placement by looking at a block.
        // Auto-aim is reserved for charging/exploding, so we force the manual
        // path here even when Auto Aim is enabled.
        boolean useAutoAim = autoAim.value && !useHeldAnchorSlot;
        BlockUtils.PlacementHit autoAimHit = null;
        BlockHitResult manualHit = null;
        boolean useAutoAimPlacement = false;

        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        if (useAutoAim) {
            autoAimHit = findBestPlacementHit(partialTick);
            if (autoAimHit != null) {
                useAutoAimPlacement = true;
            } else {
                manualHit = currentBlockHit();
            }
            if (manualHit == null && !useAutoAimPlacement) {
                if (!holdMode) clickQueued = false;
                handleInactive();
                return;
            }
        } else {
            manualHit = currentBlockHit();
            if (manualHit == null) {
                if (!holdMode) clickQueued = false;
                handleInactive();
                return;
            }
        }

        int anchorSlot = useHeldAnchorSlot
                ? mc.player.getInventory().getSelectedSlot()
                : InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.RESPAWN_ANCHOR));
        if (anchorSlot == -1) {
            clickQueued = false;
            handleInactive();
            return;
        }

        restoringSwap = false;
        if (!useHeldAnchorSlot && !SwapStateManager.swapToIfNeeded(this, anchorSlot, false, -1, false)) return;
        if (placeCooldown > 0) return;
        if (!mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) return;

        BlockHitResult hitResult;
        BlockPos placedAnchorPos;
        if (useAutoAimPlacement) {
            if (autoAimHit == null || !isReadyToPlace(autoAimHit, partialTick)) return;
            hitResult = autoAimHit.hitResult();
            placedAnchorPos = autoAimHit.placementPos();
        } else {
            hitResult = manualHit;
            placedAnchorPos = placedAnchorPosOf(hitResult);
            if (!isManualAnchorPlacementValid(placedAnchorPos)) {
                if (!holdMode) clickQueued = false;
                handleInactive();
                return;
            }
        }

        InteractionResult result = BlockUtils.interactWithBlock(hitResult, true);
        placeCooldown = VANILLA_PLACE_COOLDOWN_TICKS;
        if (result.consumesAction()) rotation.markInteraction();

        if (result.consumesAction() && placedAnchorPos != null) {
            beginAnchorSequence(placedAnchorPos, rollChargeCount());
        } else if (!holdMode) {
            finishAnchorSequence();
        }

        if (holdMode && !result.consumesAction()) {
            placeCooldown = Math.max(placeCooldown, 1);
        }
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onMouseUpdate(MouseUpdateEvent event) {
        if (!autoAim.value || isNull() || mc.screen != null || !isActionActive()
                || (shouldHandleSameUseKey() && !isAnchorSequenceActive())) {
            disengageRotations();
            return;
        }

        Vec3 aimPoint = findActiveAimPoint();
        if (aimPoint == null) {
            disengageRotations();
            return;
        }

        boolean rotated = RotationUtils.aim(this)
                .priority(EventSubscribe.Priority.HIGH)
                .silent(silentAim.value)
                .speed(aimSpeed.intValue())
                .aimType(RotationUtils.AimType.REGULAR)
                .to(aimPoint);

        if (!rotated) {
            disengageRotations();
            return;
        }
        wasAiming = true;
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onMove(EventMove.Pre event) {
        if (!autoAim.value || !wasAiming || !silentAim.value) return;
        if (!RotationUtils.isControlledBy(this) || !RotationUtils.hasSilentRotation()) return;

        event.setYaw(RotationUtils.getSilentYaw());
        event.setPitch(RotationUtils.getSilentPitch());
    }

    // -- Sequence state machine --

    private void beginAnchorSequence(BlockPos anchorPos, int chargeCount) {
        activeAnchorPos = anchorPos;
        remainingCharges = Math.max(0, chargeCount - getAnchorCharge(anchorPos));
        anchorStep = safeAnchor.value ? AnchorStep.PLACE_SAFETY : AnchorStep.CHARGE;
        nextAnchorActionTick = mc.player.tickCount + (anchorStep == AnchorStep.CHARGE ? rollTickDelay(chargeDelay) : 0);
        restoringSwap = false;
        cachedAnchorAim = AnchorAimSearch.findClearAimPoint(anchorPos, partialTick());
        safetyStepStartMillis = System.currentTimeMillis();
    }

    private void continueAnchorSequence() {
        if (!isRespawnAnchor(activeAnchorPos) || !isAnchorWithinInteractionRange(activeAnchorPos)) {
            finishAnchorSequence();
            return;
        }
        if (mc.player.tickCount < nextAnchorActionTick) return;

        switch (anchorStep) {
            case PLACE_SAFETY -> placeSafetyBlock();
            case CHARGE -> chargeActiveAnchor();
            case EXPLODE -> explodeActiveAnchor();
        }
    }

    private void finishAnchorSequence() {
        resetAnchorSequence();
        if (isHold() && InputHandler.isBindDown(activateBind())) {
            if (!repeat.value) {
                holdCycleCompleted = true;
                scheduleSwapBack(1);
                disengageRotations();
            } else if (shouldHandleSameUseKey()) {
                scheduleSwapBack(1);
            }
            return;
        }
        holdCycleCompleted = false;
        clickQueued = false;
        scheduleSwapBack(1);
    }

    private void resetAnchorSequence() {
        anchorStep = null;
        activeAnchorPos = null;
        pendingUseKeyAnchorPos = null;
        remainingCharges = 0;
        nextAnchorActionTick = 0;
        cachedAnchorAim = null;
    }

    private boolean isAnchorSequenceActive() {
        return anchorStep != null && activeAnchorPos != null;
    }

    private void advanceToChargeStep() {
        anchorStep = AnchorStep.CHARGE;
        nextAnchorActionTick = mc.player.tickCount + rollTickDelay(chargeDelay);
    }

    private void beginExplodeStepOrFinish() {
        if (getAnchorCharge(activeAnchorPos) <= 0) {
            finishAnchorSequence();
            return;
        }
        anchorStep = AnchorStep.EXPLODE;
        nextAnchorActionTick = mc.player.tickCount + rollTickDelay(explodeDelay);
    }

    // -- Step: PLACE_SAFETY --

    private void placeSafetyBlock() {
        // Build the safety candidate list ONCE per tick instead of three times.
        List<BlockPos> ranked = rankedSafetyCandidates();

        if (!shouldPlaceSafety(ranked)) {
            advanceToChargeStep();
            return;
        }

        int timeoutMs = safetyTimeout.intValue();
        if (timeoutMs > 0 && System.currentTimeMillis() - safetyStepStartMillis >= timeoutMs) {
            advanceToChargeStep();
            return;
        }

        boolean useAutoAim = autoAim.value;
        BlockHitResult hitResult;
        float partialTick = partialTick();

        if (useAutoAim) {
            BlockUtils.PlacementHit placementHit = pickSafetyPlacement(ranked);
            if (placementHit == null) return;
            if (!isReadyToPlace(placementHit, partialTick)) return;
            hitResult = placementHit.hitResult();
        } else {
            BlockHitResult playerHit = currentBlockHit();
            if (playerHit == null) return;
            BlockPos lookedAt = playerHit.getBlockPos();
            if (lookedAt.equals(activeAnchorPos)) return;

            BlockPos placement = lookedAt.relative(playerHit.getDirection());
            if (placement.equals(activeAnchorPos)) return;
            if (!BlockUtils.isValidPlacement(placement)) return;
            if (BlockUtils.collidesWithPlayer(placement)) return;
            if (BlockUtils.collidesWithBlockingEntity(placement)) return;
            hitResult = playerHit;
        }

        int safetySlot = findSafetyBlockSlot();
        if (safetySlot == -1) {
            advanceToChargeStep();
            return;
        }

        if (!SwapStateManager.swapToIfNeeded(this, safetySlot, false, -1, false)) return;
        if (placeCooldown > 0) return;
        if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) return;

        InteractionResult result = BlockUtils.interactWithBlock(hitResult, true);
        placeCooldown = VANILLA_PLACE_COOLDOWN_TICKS;
        if (result.consumesAction()) {
            rotation.markInteraction();
            advanceToChargeStep();
        }
    }

    private boolean shouldPlaceSafety(List<BlockPos> ranked) {
        if (!safeAnchor.value) return false;
        if (mc.player == null || mc.level == null || activeAnchorPos == null) return false;

        // Below the damage threshold the explosion isn't worth a glowstone.
        float threshold = (float) minDamage.value;
        if (threshold > 0.0f && ExplosionUtils.getAnchorDamageTo(mc.player, activeAnchorPos) < threshold) {
            return false;
        }

        // If the closest-to-anchor neighbor is already a solid block, we're already covered.
        BlockPos ideal = ranked.isEmpty() ? null : ranked.get(0);
        return ideal == null || !hasExistingSafetyBlock(ideal);
    }

    /**
     * 8 horizontal neighbors of the player's feet, restricted to the half-plane
     * toward the anchor (so the safety can never end up "to the right" or
     * behind us), sorted by distance to the anchor center -- the spot that
     * sits most directly between us and the anchor comes first.
     */
    private List<BlockPos> rankedSafetyCandidates() {
        List<BlockPos> ordered = new ArrayList<>(8);
        if (mc.player == null || activeAnchorPos == null) return ordered;

        float partialTick = partialTick();
        BlockPos feet = BlockPos.containing(mc.player.getPosition(partialTick));
        Vec3 feetCenter = Vec3.atCenterOf(feet);
        Vec3 anchorCenter = Vec3.atCenterOf(activeAnchorPos);
        Vec3 toAnchor = anchorCenter.subtract(feetCenter);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos pos = feet.offset(dx, 0, dz);
                Vec3 toCand = Vec3.atCenterOf(pos).subtract(feetCenter);
                if (toCand.x * toAnchor.x + toCand.z * toAnchor.z <= 0) continue; // behind / perpendicular
                ordered.add(pos);
            }
        }
        ordered.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(anchorCenter)));
        return ordered;
    }

    private BlockUtils.PlacementHit pickSafetyPlacement(List<BlockPos> ranked) {
        for (BlockPos pos : ranked) {
            BlockUtils.PlacementHit hit = trySafetyPlacementAt(pos);
            if (hit != null) return hit;
        }
        return null;
    }

    private BlockUtils.PlacementHit trySafetyPlacementAt(BlockPos pos) {
        if (pos == null || mc.level == null || mc.player == null) return null;
        if (pos.equals(activeAnchorPos)) return null;
        if (!BlockUtils.isValidPlacement(pos)) return null;
        if (!BlockUtils.hasSupportingFace(pos)) return null;
        if (BlockUtils.collidesWithPlayer(pos)) return null;
        if (BlockUtils.collidesWithBlockingEntity(pos)) return null;

        BlockUtils.PlacementHit hit = BlockUtils.findPlacementHit(pos, canPlaceLegit.value);
        if (hit == null) return null;

        Vec3 eye = mc.player.getEyePosition(partialTick());
        double range = mc.player.blockInteractionRange();
        if (eye.distanceToSqr(hit.hitResult().getLocation()) > range * range) return null;
        return hit;
    }

    private boolean hasExistingSafetyBlock(BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced();
    }

    private int findSafetyBlockSlot() {
        int slot = InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.GLOWSTONE));
        if (slot != -1) return slot;
        return InventoryUtils.findItemWithPredicateInHotbar(stack ->
                stack.getItem() instanceof BlockItem && !stack.is(Items.RESPAWN_ANCHOR));
    }

    // -- Step: CHARGE --

    private void chargeActiveAnchor() {
        int currentCharge = getAnchorCharge(activeAnchorPos);
        if (remainingCharges <= 0 || currentCharge >= MAX_ANCHOR_CHARGES) {
            beginExplodeStepOrFinish();
            return;
        }

        int glowstoneSlot = InventoryUtils.findItemWithPredicateInHotbar(stack -> stack.is(Items.GLOWSTONE));
        if (glowstoneSlot == -1) {
            remainingCharges = 0;
            beginExplodeStepOrFinish();
            return;
        }

        if (!SwapStateManager.swapToIfNeeded(this, glowstoneSlot, false, -1, false)) return;
        if (!mc.player.getMainHandItem().is(Items.GLOWSTONE)) return;

        BlockHitResult hitResult = getReadyAnchorHit(activeAnchorPos);
        if (hitResult == null) return;

        InteractionResult result = BlockUtils.interactWithBlock(hitResult, true);
        if (!result.consumesAction()) return;

        rotation.markInteraction();
        remainingCharges--;
        if (remainingCharges <= 0) {
            anchorStep = AnchorStep.EXPLODE;
            nextAnchorActionTick = mc.player.tickCount + rollTickDelay(explodeDelay);
            return;
        }
        nextAnchorActionTick = mc.player.tickCount + rollTickDelay(chargeDelay);
    }

    // -- Step: EXPLODE --

    private void explodeActiveAnchor() {
        int currentCharge = getAnchorCharge(activeAnchorPos);
        if (currentCharge <= 0) {
            finishAnchorSequence();
            return;
        }

        int targetSlot = explodeSlot.intValue() - 1;
        if (!SwapStateManager.swapToIfNeeded(this, targetSlot, false, -1, false)) return;

        // Glowstone in the explode slot would just charge again; bail unless the anchor is already maxed.
        ItemStack explodeStack = mc.player.getInventory().getItem(targetSlot);
        if (explodeStack.is(Items.GLOWSTONE) && currentCharge < MAX_ANCHOR_CHARGES) {
            finishAnchorSequence();
            return;
        }

        BlockHitResult hitResult = getReadyAnchorHit(activeAnchorPos);
        if (hitResult == null) return;

        InteractionResult result = BlockUtils.interactWithBlock(hitResult, true);
        if (!result.consumesAction()) return;

        rotation.markInteraction();
        placeCooldown = VANILLA_PLACE_COOLDOWN_TICKS;
        finishAnchorSequence();
    }

    // -- Aim picking --

    private Vec3 findActiveAimPoint() {
        if (isAnchorSequenceActive()) {
            if (anchorStep == AnchorStep.PLACE_SAFETY) {
                List<BlockPos> ranked = rankedSafetyCandidates();
                if (shouldPlaceSafety(ranked)) {
                    BlockUtils.PlacementHit safetyHit = pickSafetyPlacement(ranked);
                    if (safetyHit != null) return safetyHit.hitResult().getLocation();
                }
            }
            return getAnchorAimPoint(activeAnchorPos);
        }

        BlockHitResult anchorHit = currentBlockHit();
        if (anchorHit != null && isRespawnAnchor(anchorHit.getBlockPos())) {
            return anchorHit.getLocation();
        }

        float partialTick = partialTick();
        BlockUtils.PlacementHit placementHit = findBestPlacementHit(partialTick);
        if (placementHit != null) return placementHit.hitResult().getLocation();

        BlockHitResult manualHit = currentBlockHit();
        return manualHit != null ? manualHit.getLocation() : null;
    }

    private Vec3 getAnchorAimPoint(BlockPos anchorPos) {
        if (anchorPos == null) return null;
        if (mc.player == null) return Vec3.atCenterOf(anchorPos);

        float partialTick = partialTick();
        // Reuse the cached aim if it still raycasts cleanly. A block placed
        // since the sequence started (e.g. our safety glowstone) can occlude
        // the original corner -- in that case re-pick.
        if (cachedAnchorAim != null && anchorPos.equals(activeAnchorPos)) {
            Vec3 eye = mc.player.getEyePosition(partialTick);
            if (AnchorAimSearch.canHitAnchor(eye, cachedAnchorAim, anchorPos)) return cachedAnchorAim;
            cachedAnchorAim = AnchorAimSearch.findClearAimPoint(anchorPos, partialTick);
            return cachedAnchorAim != null ? cachedAnchorAim : Vec3.atCenterOf(anchorPos);
        }
        Vec3 fresh = AnchorAimSearch.findClearAimPoint(anchorPos, partialTick);
        return fresh != null ? fresh : Vec3.atCenterOf(anchorPos);
    }

    private boolean isReadyToPlace(BlockUtils.PlacementHit placementHit, float partialTick) {
        float yaw = effectiveYaw();
        float pitch = effectivePitch();

        // Grim's DuplicateRotPlace compares pitch delta against the previous
        // place packet. Falling changes the needed pitch every tick, so allow
        // those non-duplicate deltas instead of waiting until we land.
        if (!rotation.isDuplicateRotPlaceSafe(ROTATION_SETTLE_THRESHOLD)) return false;

        if (!canPlaceLegit.value) {
            return RotationUtils.rayIntersectsBlock(yaw, pitch, placementHit.hitResult().getBlockPos(), partialTick);
        }

        BlockHitResult raycast = RotationUtils.raycastFromRotation(yaw, pitch, partialTick);
        if (raycast == null || raycast.getType() != HitResult.Type.BLOCK) return false;
        if (!raycast.getBlockPos().equals(placementHit.hitResult().getBlockPos())) return false;
        return raycast.getDirection() == placementHit.hitResult().getDirection();
    }

    private BlockHitResult getReadyAnchorHit(BlockPos anchorPos) {
        if (!isAnchorWithinInteractionRange(anchorPos)) return null;

        // Grim's DuplicateRotPlace compares pitch delta against the previous
        // place packet. Falling changes the needed pitch every tick, so allow
        // those non-duplicate deltas instead of waiting until we land.
        if (!rotation.isDuplicateRotPlaceSafe(ROTATION_SETTLE_THRESHOLD)) return null;

        float yaw = effectiveYaw();
        float pitch = effectivePitch();
        float partialTick = partialTick();

        if (!canPlaceLegit.value) {
            if (!RotationUtils.rayIntersectsBlock(yaw, pitch, anchorPos, partialTick)) return null;
            return new BlockHitResult(getAnchorAimPoint(anchorPos), Direction.UP, anchorPos, false);
        }

        BlockHitResult currentHit = currentBlockHit();
        if (currentHit != null && currentHit.getBlockPos().equals(anchorPos)) return currentHit;

        BlockHitResult raycast = RotationUtils.raycastFromRotation(yaw, pitch, partialTick);
        if (raycast == null || raycast.getType() != HitResult.Type.BLOCK) return null;
        if (!raycast.getBlockPos().equals(anchorPos)) return null;
        return raycast;
    }

    // -- Best-placement search (anchor placement candidate) --

    private BlockUtils.PlacementHit findBestPlacementHit(float partialTick) {
        Vec3 playerEye = mc.player.getEyePosition(partialTick);
        LivingEntity target = TargetUtils.findClosestResolvedTarget(playerEye, partialTick);
        if (target == null) return null;

        double blockRange = mc.player.blockInteractionRange();
        double blockRangeSq = blockRange * blockRange;

        Vec3 targetPos = target.getPosition(partialTick);
        Vec3 offset = targetPos.subtract(target.position());
        AABB targetBox = target.getBoundingBox().move(offset);
        Vec3 targetFeet = new Vec3(targetPos.x, targetBox.minY, targetPos.z);

        int floorX = Mth.floor(targetFeet.x);
        int floorY = Mth.floor(targetFeet.y);
        int floorZ = Mth.floor(targetFeet.z);

        BlockUtils.PlacementHit bestHit = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos pos = new BlockPos(floorX + dx, floorY + dy, floorZ + dz);

                    if (!BlockUtils.isValidPlacement(pos)) continue;
                    if (!BlockUtils.hasSupportingFace(pos)) continue;

                    BlockUtils.PlacementHit hit = BlockUtils.findPlacementHit(pos, canPlaceLegit.value);
                    if (hit == null) continue;
                    if (BlockUtils.collidesWithPlayer(pos)) continue;
                    if (BlockUtils.hasBlockingEntity(new AABB(pos))) continue;
                    if (playerEye.distanceToSqr(hit.hitResult().getLocation()) > blockRangeSq) continue;

                    Vec3 blockCenter = Vec3.atCenterOf(pos);
                    float exposure = ExplosionUtils.getSeenPercent(blockCenter, targetBox, target);
                    double feetDistSq = targetFeet.distanceToSqr(blockCenter);
                    // Prefer high-exposure hits; break ties by proximity to feet.
                    double score = (1.0 - exposure) * 100.0 + feetDistSq * 0.01;

                    if (score < bestScore) {
                        bestScore = score;
                        bestHit = hit;
                    }
                }
            }
        }
        return bestHit;
    }

    // -- Misc helpers --

    private float partialTick() {
        return mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    private float effectiveYaw() {
        return RotationHandler.isHasSilentRotation() ? RotationHandler.getServerYaw() : mc.player.getYRot();
    }

    private float effectivePitch() {
        return RotationHandler.isHasSilentRotation() ? RotationHandler.getServerPitch() : mc.player.getXRot();
    }

    private boolean isActionActive() {
        if (isHold()) {
            if (!InputHandler.isBindDown(activateBind())) return false;
            return repeat.value || !holdCycleCompleted || isAnchorSequenceActive();
        }
        return clickQueued || isAnchorSequenceActive();
    }

    private boolean shouldHandleSameUseKey() {
        if (mc == null || mc.options == null || mc.options.keyUse == null) return false;
        return activateBind() != -1 && activateBind() == bindCodeOf(mc.options.keyUse);
    }

    private int bindCodeOf(KeyMapping keyMapping) {
        if (keyMapping == null) return -1;
        InputConstants.Key key = ((EchoKeyMappingAccessor) keyMapping).kuro$getKey();
        if (key == null) return -1;
        if (key.getType() == InputConstants.Type.MOUSE) return 0x80000000 | key.getValue();
        return key.getValue();
    }

    private boolean isHoldingRespawnAnchor() {
        return mc.player != null && mc.player.getMainHandItem().is(Items.RESPAWN_ANCHOR);
    }

    private BlockHitResult currentBlockHit() {
        if (!(mc.hitResult instanceof BlockHitResult blockHit)) return null;
        if (blockHit.getType() != HitResult.Type.BLOCK) return null;
        return blockHit;
    }

    private BlockPos consumePendingUseKeyAnchorPos() {
        BlockPos pos = pendingUseKeyAnchorPos;
        pendingUseKeyAnchorPos = null;
        if (pos == null) return null;
        return isRespawnAnchor(pos) ? pos : null;
    }

    private BlockPos anchorPosOf(HitResult hitResult) {
        if (!(hitResult instanceof BlockHitResult blockHit)) return null;
        if (blockHit.getType() != HitResult.Type.BLOCK) return null;
        return isRespawnAnchor(blockHit.getBlockPos()) ? blockHit.getBlockPos() : null;
    }

    private BlockPos placedAnchorPosOf(BlockHitResult hitResult) {
        return hitResult == null ? null : hitResult.getBlockPos().relative(hitResult.getDirection());
    }

    private boolean isManualAnchorPlacementValid(BlockPos pos) {
        if (pos == null) return false;
        if (!BlockUtils.isValidPlacement(pos)) return false;
        if (BlockUtils.collidesWithPlayer(pos)) return false;
        return !BlockUtils.collidesWithBlockingEntity(pos);
    }

    private boolean isRespawnAnchor(BlockPos pos) {
        if (pos == null || mc.level == null) return false;
        return BlockUtils.isBlockAtPosition(pos, Blocks.RESPAWN_ANCHOR);
    }

    private int getAnchorCharge(BlockPos pos) {
        if (!isRespawnAnchor(pos)) return 0;
        return mc.level.getBlockState(pos).getValue(RespawnAnchorBlock.CHARGE);
    }

    private boolean isAnchorWithinInteractionRange(BlockPos anchorPos) {
        return mc.player != null && anchorPos != null && mc.player.isWithinBlockInteractionRange(anchorPos, 1.0);
    }

    private void handleInactive() {
        resetAnchorSequence();
        if (SwapStateManager.isOwnerActive(this) && !restoringSwap) scheduleSwapBack(1);
        disengageRotations();
    }

    private void scheduleSwapBack(int delayTicks) {
        if (!SwapStateManager.isOwnerActive(this)) return;
        int activeSlot = SwapStateManager.getActiveTargetSlot(this);
        if (activeSlot == -1) {
            SwapStateManager.cancel(this, true);
            restoringSwap = false;
            return;
        }
        SwapStateManager.swapToIfNeeded(this, activeSlot, false, delayTicks, true);
        restoringSwap = true;
    }

    private void disengageRotations() {
        if (!RotationUtils.isControlledBy(this)) {
            wasAiming = false;
            return;
        }
        if (RotationHandler.isHasSilentRotation() || silentAim.value) {
            if (RotationUtils.cleanup(this, true, aimSpeed.intValue(), aimSpeed.intValue(), RotationUtils.AimType.REGULAR, false)) return;
        } else {
            RotationUtils.stopTracking();
        }
        wasAiming = false;
    }

    // Random rolls -- delegate to Range.getRandom() and clamp to int.
    private int rollChargeCount() {
        return Mth.clamp(Math.round(charges.getRandom()), 1, MAX_ANCHOR_CHARGES);
    }

    private int rollTickDelay(Range setting) {
        return Math.max(0, Math.round(setting.getRandom()));
    }
}
