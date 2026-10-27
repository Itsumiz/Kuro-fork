package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.CombatControl;
import dev.kuro.client.util.CombatReach;
import dev.kuro.client.util.Friends;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Real Fabric port of Vape Triggerbot for Minecraft 1.21.x.
 *
 * Same control surface and decision order as Vape:
 *  canAttack gates → crosshair entity → select-first-hit → mouse-over delay →
 *  isAttackReady(extraDelay offset) → optional miss chance → swing via AttackPackets.
 *
 * Not Vape bytecode; behaviour reimplemented on Kuro systems
 * (AttackPackets, AutoWeapon, CombatReach, CombatControl).
 */
public class TriggerBot extends Module {

    // ---- Vape setting parity ----
    private final Setting.Num extraDelayMin = add(new Setting.Num("Extra delay min (ticks)", 0, -20, 20, 1));
    private final Setting.Num extraDelayMax = add(new Setting.Num("Extra delay max (ticks)", 0, -20, 20, 1));
    private final Setting.Num mouseOverMin = add(new Setting.Num("Mouse over delay min (ms)", 0, 0, 200, 10));
    private final Setting.Num mouseOverMax = add(new Setting.Num("Mouse over delay max (ms)", 0, 0, 200, 10));
    // ---- Late fire: wait N game ticks after the weapon is fully charged and the target is under the crosshair ----
    private final Setting.Bool lateFire = add(new Setting.Bool("Late fire", false));
    private final Setting.Num lateMin = add(new Setting.Num("Late fire delay min (ticks)", 1, 0, 20, 1));
    private final Setting.Num lateMax = add(new Setting.Num("Late fire delay max (ticks)", 2, 0, 20, 1));
    private final Setting.Bool requireMouseDown = add(new Setting.Bool("Require mouse down", false));
    private final Setting.Bool ignoreActivationClick = add(new Setting.Bool("Ignore activation click", false));
    private final Setting.Bool airCrits = add(new Setting.Bool("Air crits", false));
    private final Setting.Bool shieldCheck = add(new Setting.Bool("Shield check", false));
    private final Setting.Bool selectFirstHit = add(new Setting.Bool("Select first hit", false));
    private final Setting.Num targetMissChance = add(new Setting.Num("Target miss chance %", 0, 0, 100, 1));
    private final Setting.Num earlyHitChance = add(new Setting.Num("Early hit chance %", 0, 0, 100, 1));
    private final Setting.Bool weaponsOnly = add(new Setting.Bool("Weapons only", false));
    private final Setting.Bool players = add(new Setting.Bool("Players", true));
    private final Setting.Bool ignoreBots = add(new Setting.Bool("Ignore bots (Anti Bot)", true));
    private final Setting.Bool mobs = add(new Setting.Bool("Other mobs", false));

    // ---- state (mirrors Vape fields) ----
    private long attackTimerMs;
    private long mouseOverTimerMs;
    private long mouseOverDelayMs;
    private int mouseOverTargetId = -1;
    private boolean mouseDownLatch;
    private boolean waitingForFirstHit;
    private int selectTargetId = -1;
    private int lastHurtTime;
    private float rolledExtraDelay;   // ticks, may be negative
    private int earlyHitTicks;
    private int lateTicks;            // full-charge ticks waited so far on the current target
    private int rolledLate;           // how many to wait before this attack
    private int lateTargetId = -1;

    public TriggerBot() {
        super("Trigger Bot", Category.COMBAT);
    }

    @Override
    protected void onEnable() {
        attackTimerMs = 0;
        mouseOverTargetId = -1;
        mouseOverDelayMs = 0;
        mouseOverTimerMs = 0;
        mouseDownLatch = false;
        resetSelectState();
        rolledExtraDelay = rollExtraDelay();
        earlyHitTicks = 0;
        lastHurtTime = 0;
        resetLate();
    }

    @Override
    protected void onDisable() {
        mouseDownLatch = false;
        resetSelectState();
        mouseOverTargetId = -1;
    }

    private float rollExtraDelay() {
        double lo = Math.min(extraDelayMin.value, extraDelayMax.value);
        double hi = Math.max(extraDelayMin.value, extraDelayMax.value);
        if (lo == hi) return (float) lo;
        return (float) (lo + ThreadLocalRandom.current().nextDouble() * (hi - lo));
    }

    /** New wait for the next attack: random whole ticks between min and max. */
    private void resetLate() {
        int lo = (int) Math.min(lateMin.value, lateMax.value);
        int hi = (int) Math.max(lateMin.value, lateMax.value);
        rolledLate = lo == hi ? lo : lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
        lateTicks = 0;
        lateTargetId = -1;
    }

    private long rollMouseOverMs() {
        int lo = (int) Math.min(mouseOverMin.value, mouseOverMax.value);
        int hi = (int) Math.max(mouseOverMin.value, mouseOverMax.value);
        if (lo == hi) return lo;
        return lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
    }

    private boolean attackKeyDown() {
        return mc.getWindow() != null
                && GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private void resetSelectState() {
        selectTargetId = -1;
        waitingForFirstHit = false;
    }

    /** Vape canAttack(requireActivation) */
    private boolean canAttack(boolean requireActivation) {
        if (mc.player == null) return false;
        if (mc.screen != null) return false;
        if (Freecam.active() || mc.player.isSpectator()) return false;
        if (mc.player.isUsingItem()) return false;
        if (CombatControl.blocked(this)) return false;

        if (requireActivation && requireMouseDown.value) {
            if (!attackKeyDown()) return false;
        }
        if (weaponsOnly.value && !isWeapon(mc.player.getMainHandItem())) return false;

        if (airCrits.value && !mc.player.onGround()) {
            // only allow while falling (will crit)
            if (mc.player.getDeltaMovement().y >= 0.0 || mc.player.onClimbable() || mc.player.isInWater()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Vape isAttackReady(cooldownOffset): attack progress with offset in ticks.
     * Positive offset = wait longer after full cooldown; negative = allow before 1.0.
     */
    private boolean isAttackReady(float cooldownOffsetTicks) {
        if (mc.player == null) return false;
        if (AutoWeapon.bypass(crosshairLiving())) return true; // mace smash / shield-break axe
        // Convert tick offset to progress: ~1 tick ≈ 0.05 on a 20-tick (1.0s) sword recharge is wrong;
        // Vape uses AttackCooldownUtil.isAttackReady(offset). Approximate: each tick ≈ 50ms of cooldown clock.
        float progress = mc.player.getAttackStrengthScale(0.5f);
        // Offset: negative means ready earlier (lower progress threshold)
        float threshold = 1.0f;
        if (cooldownOffsetTicks < 0) {
            // allow at (1 + offset/20) roughly for a 1-second weapon
            threshold = Math.max(0.0f, 1.0f + cooldownOffsetTicks / 20.0f);
        } else if (cooldownOffsetTicks > 0) {
            // must be fully ready, and we track ticks after ready separately
            threshold = 1.0f;
        }
        if (progress < threshold) return false;
        if (cooldownOffsetTicks > 0) {
            // need continuous full-charge for N ticks — handled by caller via earlyHitTicks pattern
            return progress >= 1.0f;
        }
        return true;
    }

    private LivingEntity crosshairLiving() {
        HitResult hr = mc.hitResult;
        if (hr instanceof EntityHitResult er && er.getEntity() instanceof LivingEntity le) return le;
        return null;
    }

    private boolean isValidTarget(Entity entity) {
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return false;
        if (entity == mc.player) return false;
        if (Friends.shield(entity) || (ignoreBots.value && AntiBot.isBot(entity))) return false;
        if (entity instanceof Player p) {
            if (!players.value || p.isSpectator()) return false;
            if (shieldCheck.value && AutoWeapon.wouldBlockHit(p)
                    && !AutoWeapon.shieldBreakActive() && !AutoWeapon.bypass(p)) {
                return false;
            }
            return true;
        }
        return mobs.value;
    }

    private boolean hasMouseOverDelayElapsed(Entity entity) {
        int id = entity.getId();
        if (mouseOverTargetId != id) {
            mouseOverTargetId = id;
            mouseOverDelayMs = rollMouseOverMs();
            mouseOverTimerMs = System.currentTimeMillis();
        }
        return System.currentTimeMillis() - mouseOverTimerMs >= mouseOverDelayMs;
    }

    private boolean updateSelectFirstHit(Entity entity, boolean wasJustHurt) {
        if (!selectFirstHit.value) {
            resetSelectState();
            return true;
        }
        int id = entity.getId();
        if (selectTargetId != id) {
            selectTargetId = id;
            waitingForFirstHit = true;
        }
        if (waitingForFirstHit && wasJustHurt) {
            waitingForFirstHit = false;
        }
        return !waitingForFirstHit;
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        int hurtTime = mc.player.hurtTime;
        boolean wasJustHurt = lastHurtTime <= 0 && hurtTime > 0;
        lastHurtTime = hurtTime;

        // Require mouse down release tracking (Vape resets select state when mouse lifts)
        if (requireMouseDown.value) {
            boolean down = attackKeyDown();
            if (mouseDownLatch && !down) {
                mouseDownLatch = false;
                resetSelectState();
            }
            if (down) mouseDownLatch = true;
        }

        // Global 50ms throttle like Vape attackTimer
        long now = System.currentTimeMillis();
        if (now - attackTimerMs < 50L) return;

        if (!canAttack(true)) return;

        // cooldownOffset = -extraDelay + earlyHitTicks  (Vape formula)
        float cooldownOffset = -rolledExtraDelay + earlyHitTicks;

        LivingEntity entity = crosshairLiving();
        if (entity != null && isValidTarget(entity)) {
            if (!updateSelectFirstHit(entity, wasJustHurt)) return;
            if (!hasMouseOverDelayElapsed(entity)) return;
            if (!isAttackReady(cooldownOffset)) {
                // early hit chance: sometimes treat as ready before full charge
                if (earlyHitChance.value > 0
                        && mc.player.getAttackStrengthScale(0.5f) >= 0.7f
                        && ThreadLocalRandom.current().nextDouble() * 100.0 < earlyHitChance.value) {
                    // allow through
                } else {
                    return;
                }
            }

            // Positive extra delay: wait N ticks at full charge
            if (rolledExtraDelay > 0 && mc.player.getAttackStrengthScale(0.5f) >= 1.0f) {
                earlyHitTicks++;
                if (earlyHitTicks < rolledExtraDelay) return;
            }

            // Late fire: count full-charge ticks on this target (game ticks, not wall clock), fire only after the wait.
            // Mace smash / shield-break bypass attacks are time critical and skip it.
            if (lateFire.value && !AutoWeapon.bypass(entity)) {
                if (entity.getId() != lateTargetId) { lateTargetId = entity.getId(); lateTicks = 0; }
                if (mc.player.getAttackStrengthScale(0.5f) < 1.0f) { lateTicks = 0; return; }
                if (lateTicks < rolledLate) { lateTicks++; return; }
            }

            if (targetMissChance.value > 0
                    && ThreadLocalRandom.current().nextDouble() * 100.0 < targetMissChance.value) {
                attackTimerMs = now;
                rolledExtraDelay = rollExtraDelay();
                earlyHitTicks = 0;
                resetLate();
                return;
            }

            AutoWeapon.onTarget(entity);
            if (!AutoWeapon.readyToAttack() && !AutoWeapon.bypass(entity)) return;

            AttackPackets.hit(entity, e -> e.isAlive() && isValidTarget(e) && CombatReach.inReach(e));
            attackTimerMs = now;
            rolledExtraDelay = rollExtraDelay();
            earlyHitTicks = 0;
            resetLate();
            return;
        }

        // No entity under crosshair — reset mouse-over target id (Vape does this)
        mouseOverTargetId = -1;
        mouseOverDelayMs = 0;
        lateTicks = 0;
        lateTargetId = -1;
    }

    private static boolean isWeapon(ItemStack s) {
        if (s.isEmpty()) return false;
        return s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES)
                || s.is(Items.MACE) || s.is(Items.TRIDENT);
    }
}
