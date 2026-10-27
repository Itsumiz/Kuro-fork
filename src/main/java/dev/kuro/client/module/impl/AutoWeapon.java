package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.Friends;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Holder;
import net.minecraft.tags.ItemTags;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Auto Weapon, modelled on LiquidBounce's AutoWeapon. It picks the best weapon in your hotbar for the target.
 * The pick is a plain function of the target's CURRENT state, so there is no multi-step sequence to get stuck in:
 *
 *  - Auto shield break: the target would block this hit  -> axe (an axe hit disables the shield).
 *  - Auto mace:         you are falling far enough to smash -> mace (Density from the set fall height, else Breach).
 *  - Stun slam (on):    falling AND the target is blocking -> axe first. As soon as the shield is down the very same
 *                       rule picks the mace, so axe -> mace happens by itself, one hit after the other.
 *  - Otherwise:         the best of the preferred weapon types (Priority: default / knockback / damage / speed).
 *
 * Trigger Bot, Silent Aura, Kill Aura and Auto Clicker call {@link #onTarget} when they have a target (the slot is
 * changed ahead of the hit), {@link #readyToAttack} (never swap and hit in the same tick) and {@link #bypass}
 * (axe-on-a-shield and mace smashes skip the normal charge wait, like LiquidBounce's ignoreOnShieldBreak / ignoreOnMaceSmash).
 * "Change on attack" also swaps at the moment you attack, like LiquidBounce's default.
 */
public class AutoWeapon extends Module {
    private final Setting.Bool preferSword = add(new Setting.Bool("Prefer sword", true));
    private final Setting.Bool preferAxe = add(new Setting.Bool("Prefer axe", false));
    private final Setting.Bool preferMace = add(new Setting.Bool("Prefer mace", false));
    private final Setting.Num priority = add(new Setting.Num("Priority: 0 def 1 kb 2 dmg 3 spd", 0, 0, 3, 1));
    private final Setting.Bool autoShieldBreak = add(new Setting.Bool("Auto shield break", true));
    private final Setting.Bool autoMace = add(new Setting.Bool("Auto mace", true));
    private final Setting.Bool stunSlam = add(new Setting.Bool("Stun slam (axe, then mace)", true));
    private final Setting.Num densityFrom = add(new Setting.Num("Density from fall (blocks)", 3.0, 0.0, 10.0, 0.1));
    private final Setting.Bool anyMace = add(new Setting.Bool("Use any mace if none", true));
    private final Setting.Num switchBack = add(new Setting.Num("Switch back (ticks)", 20, 1, 300, 1));
    private final Setting.Bool changeOnTarget = add(new Setting.Bool("Change on target", true));
    private final Setting.Bool changeOnAttack = add(new Setting.Bool("Change on attack", false));
    private final Setting.Bool fromWeaponOnly = add(new Setting.Bool("Only from weapon", true));
    private final Setting.Num followUpGap = add(new Setting.Num("Follow-up gap (ticks)", 1, 1, 10, 1));
    private final Setting.Num assumeDown = add(new Setting.Num("Assume shield down after axe (ticks, 0 = off)", 4, 0, 10, 1));
    private final Setting.Bool debug = add(new Setting.Bool("Debug (chat log)", false));

    private static AutoWeapon instance;

    private int clientTick;
    private int swapBackSlot = -1;     // the slot to go back to (-1 = nothing to restore)
    private int ourSlot = -1;          // the slot we selected last; if you scroll away from it we stop managing
    private int restoreAt;
    private int swapTick = -10;
    private int lastAttackTick = -100;
    private int axedId = -1;           // the player our last stun-slam axe hit went to
    private int axedTick = -100;       // ...and when: the shield counts as down for "Assume shield down" ticks after it
    private int axeStreak;             // stun-slam axe hits in this fall; two without the shield dropping = go to the mace

    // After a smash the server resets your fall distance. The client value stays high, so we track our own:
    // falling again is measured from where the last smash happened, until you touch the ground.
    private boolean smashSpent;
    private double spentY;

    public AutoWeapon() {
        super("Auto Weapon", Category.COMBAT);
        instance = this;
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() && player == mc.player && entity instanceof LivingEntity && isEnabled()) onAttack(entity);
            return InteractionResult.PASS;
        });
    }

    @Override public boolean early() { return true; }

    @Override protected void onEnable() { resetState(); }

    @Override protected void onDisable() {
        if (swapBackSlot >= 0 && mc.player != null && BlockPlacer.selected() == ourSlot) BlockPlacer.selectForce(swapBackSlot);
        resetState();
    }

    private void resetState() {
        swapBackSlot = -1;
        ourSlot = -1;
        swapTick = -10;
        lastAttackTick = -100;
        axeStreak = 0;
        axedId = -1;
        axedTick = -100;
        smashSpent = false;
    }

    // -------------------------------------------------------------------------
    // Public API for Trigger Bot / Silent Aura / Kill Aura / Auto Clicker
    // -------------------------------------------------------------------------

    /** Charge of the item you hold (0..1). */
    public static float progress() {
        return mc.player == null ? 0f : mc.player.getAttackStrengthScale(0.5f);
    }

    /** Call when the target is known (before the hit): changes to the right weapon now if "Change on target" is on. */
    public static void onTarget(Entity target) {
        AutoWeapon m = instance;
        if (m == null || !m.isEnabled() || mc.player == null || !m.changeOnTarget.value) return;
        if (!(target instanceof LivingEntity) || mc.screen != null || Freecam.active()) return;
        if (m.busy() || !m.heldAllowed()) return;
        m.applySlot(m.determineSlot(target));
    }

    /** False in the tick a slot change was made: the hit waits one tick so the slot and the attack never share a tick. */
    public static boolean readyToAttack() {
        AutoWeapon m = instance;
        return m == null || !m.isEnabled() || m.clientTick != m.swapTick;
    }

    /**
     * True when the right tool for this target is ALREADY in your hand and the hit is a shield break or a mace smash:
     * the caller may skip its usual charge / delay wait. Spaced by "Follow-up gap" so it is one hit, not a spam.
     */
    public static boolean bypass(Entity target) {
        AutoWeapon m = instance;
        if (m == null || !m.isEnabled() || mc.player == null || !(target instanceof LivingEntity)) return false;
        if (m.clientTick - m.lastAttackTick < m.followUpGap.intValue()) return false;
        ItemStack held = mc.player.getMainHandItem();
        if (m.determineSlot(target) != BlockPlacer.selected()) return false;
        if (isMace(held)) return m.autoMace.value && m.canSmash();
        if (isAxe(held)) {
            // a second axe hit on the same player waits long enough for the client to see the shield state
            if (m.axedId == target.getId() && m.clientTick - m.lastAttackTick < Math.max(m.followUpGap.intValue(), 5)) return false;
            return m.autoShieldBreak.value && target instanceof Player p && m.blockingNow(p);
        }
        return false;
    }

    /**
     * True when the weapon this target calls for is a shield-break axe or a mace smash, whether or not it is in your
     * hand yet. Callers use it to start aiming early; the hit itself still goes through {@link #bypass}.
     */
    public static boolean wantsStep(Entity target) {
        AutoWeapon m = instance;
        if (m == null || !m.isEnabled() || mc.player == null || !(target instanceof LivingEntity)) return false;
        int slot = m.determineSlot(target);
        if (slot < 0) return false;
        ItemStack s = mc.player.getInventory().getItem(slot);
        if (isMace(s)) return m.autoMace.value && m.canSmash();
        if (isAxe(s)) return m.autoShieldBreak.value && target instanceof Player p && m.blockingNow(p);
        return false;
    }

    /** An axe hit on a blocking target is about to be followed by the mace hit (stun slam): callers keep their aim. */
    public static boolean followUpExpected(Entity target) {
        AutoWeapon m = instance;
        if (m == null || !m.isEnabled() || mc.player == null || !m.stunSlam.value || !m.autoMace.value) return false;
        return isAxe(mc.player.getMainHandItem()) && target instanceof Player p && m.blockingNow(p)
                && m.canSmash() && BlockPlacer.findSlot(AutoWeapon::isMace) >= 0;
    }

    /** True when the next hit can be a mace smash and a mace is in the hotbar. */
    public static boolean willMaceSmash() {
        AutoWeapon m = instance;
        return m != null && m.isEnabled() && mc.player != null && m.autoMace.value && m.canSmash()
                && BlockPlacer.findSlot(AutoWeapon::isMace) >= 0;
    }

    /** True while Auto Weapon is on with Auto shield break (Trigger Bot's shield check backs off then). */
    public static boolean shieldBreakActive() {
        return instance != null && instance.isEnabled() && instance.autoShieldBreak.value;
    }

    /** One-line state summary for Silent Aura's debug output. */
    public static String why(Entity t) {
        AutoWeapon m = instance;
        if (m == null || !m.isEnabled() || mc.player == null) return "weapon:off";
        return "held=" + mc.player.getMainHandItem().getItem() + " sel=" + BlockPlacer.selected()
                + " want=" + m.determineSlot(t) + " smash=" + m.canSmash() + " spent=" + m.smashSpent
                + " axed=" + (m.axedId >= 0 && m.clientTick - m.axedTick <= m.assumeDown.intValue()) + " fall=" + String.format("%.1f", m.effectiveFall()) + " bypass=" + bypass(t) + " streak=" + m.axeStreak;
    }

    // -------------------------------------------------------------------------
    // Ticks
    // -------------------------------------------------------------------------

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) return;
        clientTick++;
        if (mc.player.onGround()) { smashSpent = false; axeStreak = 0; axedId = -1; }

        // Playing by hand: while you hold attack on someone, get the weapon ready (like LiquidBounce's Auto Clicker does).
        if (changeOnTarget.value && mc.screen == null && mc.options.keyAttack.isDown()
                && mc.hitResult instanceof EntityHitResult hit && hit.getType() == HitResult.Type.ENTITY
                && hit.getEntity() instanceof Player p && p != mc.player && p.isAlive() && !Friends.shield(p)) {
            onTarget(p);
        }

        // Back to the slot we came from once nothing has needed the weapon for "Switch back" ticks.
        if (swapBackSlot >= 0 && clientTick >= restoreAt && !busy()) {
            if (BlockPlacer.selected() != ourSlot) {
                swapBackSlot = -1;                       // you changed slot yourself: leave it alone
            } else if (BlockPlacer.select(swapBackSlot)) {
                dbg("back to slot " + swapBackSlot);
                swapBackSlot = -1;                       // refused (one swap per tick) = retried next tick
            }
        }
    }

    /** Runs inside the attack (before the game sends the slot + attack packets), so a swap here is what hits. */
    private void onAttack(Entity target) {
        if (mc.player == null) return;
        if (changeOnAttack.value && !busy() && heldAllowed()) applySlot(determineSlot(target));

        ItemStack held = mc.player.getMainHandItem();
        lastAttackTick = clientTick;
        if (swapBackSlot >= 0) restoreAt = clientTick + switchBack.intValue();
        if (isMace(held) && canSmash()) {
            markSmash();
            axeStreak = 0;
            axedId = -1;
        } else if (isAxe(held) && target instanceof Player p && wouldBlockHit(p) && canSmash()) {
            axeStreak++;
            axedId = p.getId();          // shield is going down: do not wait a whole ping for the client to see it
            axedTick = clientTick;
        }
    }

    // -------------------------------------------------------------------------
    // Weapon choice
    // -------------------------------------------------------------------------

    private int determineSlot(Entity target) {
        if (mc.player == null) return -1;
        Player p = target instanceof Player pe ? pe : null;
        boolean smash = autoMace.value && canSmash();
        boolean blocks = autoShieldBreak.value && p != null && blockingNow(p);
        // No smash available: a blocking target gets the axe (LiquidBounce). Smash available: the mace, unless stun
        // slam is on, in which case the axe goes first until the shield is down (two failed axe hits = stop trying).
        boolean axeFirst = blocks && (!smash || (stunSlam.value && axeStreak < 2));

        if (axeFirst) {
            int axe = BlockPlacer.findSlot(AutoWeapon::isAxe);
            if (axe >= 0) return axe;
        }
        if (smash) {
            int mace = pickMaceSlot();
            if (mace >= 0) return mace;
        }
        return pickPreferred();
    }

    private int pickMaceSlot() {
        boolean deep = effectiveFall() >= densityFrom.value;
        ResourceKey<Enchantment> want = deep ? Enchantments.DENSITY : Enchantments.BREACH;
        int slot = BlockPlacer.findSlot(s -> isMace(s) && level(s, want) > 0);
        if (slot < 0 && anyMace.value) slot = BlockPlacer.findSlot(AutoWeapon::isMace);
        return slot;
    }

    /** Best hotbar item of the preferred types by Priority. The item already in hand wins ties (no pointless swaps). */
    private int pickPreferred() {
        var inv = mc.player.getInventory();
        int cur = BlockPlacer.selected();
        int best = -1;
        double bestScore = -1;
        ItemStack held = inv.getItem(cur);
        if (preferred(held)) { best = cur; bestScore = score(held); }
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (i == cur || s.isEmpty() || !preferred(s)) continue;
            double sc = score(s);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return best;
    }

    private boolean preferred(ItemStack s) {
        if (s.isEmpty()) return false;
        return (preferSword.value && isSword(s)) || (preferAxe.value && isAxe(s)) || (preferMace.value && isMace(s));
    }

    private double score(ItemStack s) {
        double dmg = baseDamage(s);
        return switch (priority.intValue()) {
            case 1 -> level(s, Enchantments.KNOCKBACK) * 1000.0 + dmg;
            case 2 -> dmg;
            case 3 -> attackSpeedOf(s) * 1000.0 + dmg;
            default -> typeRank(s) * 1000.0 + dmg;       // sword, then axe, then mace
        };
    }

    private static int typeRank(ItemStack s) { return isSword(s) ? 3 : isAxe(s) ? 2 : isMace(s) ? 1 : 0; }

    /** Applies the choice: one swap per tick (SwapLimit). Remembers where to go back to. */
    private boolean applySlot(int slot) {
        if (slot < 0 || mc.player == null) return false;
        int cur = BlockPlacer.selected();
        if (slot == cur) {
            if (swapBackSlot >= 0) restoreAt = clientTick + switchBack.intValue();
            return true;
        }
        if (!BlockPlacer.select(slot)) return false;
        if (swapBackSlot < 0) swapBackSlot = cur;
        ourSlot = slot;
        swapTick = clientTick;
        restoreAt = clientTick + switchBack.intValue();
        dbg("slot " + cur + " -> " + slot + " (" + mc.player.getInventory().getItem(slot).getItem() + ")");
        return true;
    }

    /** Eating, drinking, drawing a bow...: never take the slot away. */
    private boolean busy() {
        return mc.player != null && mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND;
    }

    private boolean heldAllowed() {
        return !fromWeaponOnly.value || swapBackSlot >= 0 || isWeapon(mc.player.getMainHandItem());
    }

    // -------------------------------------------------------------------------
    // Smash and shield rules
    // -------------------------------------------------------------------------

    private double effectiveFall() {
        if (mc.player == null) return 0.0;
        return smashSpent ? Math.max(0.0, spentY - mc.player.getY()) : (double) mc.player.fallDistance;
    }

    /** Vanilla MaceItem.canSmashAttack / LiquidBounce's canMaceSmash: fallen more than 1.5 blocks and not gliding. */
    private boolean canSmash() {
        return mc.player != null && !mc.player.onGround() && !mc.player.isFallFlying() && effectiveFall() > 1.5;
    }

    private void markSmash() {
        smashSpent = true;
        spentY = mc.player.getY();
        dbg("smash used, need to fall >1.5 again");
    }

    /**
     * wouldBlockHit, except that for "Assume shield down" ticks after our own stun-slam axe hit on this player the shield
     * counts as already down. The axe disables it on the server at once, but the client only learns about it a ping
     * later, which is what used to make the mace wait (or a second axe hit go out).
     */
    private boolean blockingNow(Player p) {
        if (!wouldBlockHit(p)) return false;
        int w = assumeDown.intValue();
        return !(w > 0 && axedId == p.getId() && clientTick - axedTick <= w);
    }

    /**
     * Vanilla shield check (LivingEntity.blockedByShield, LiquidBounce's wouldBlockHit): the shield blocks when the
     * attacker is in front of the holder's HORIZONTAL facing. Height is ignored, so a target you are falling onto from
     * above is judged exactly like the server will judge it.
     */
    public static boolean wouldBlockHit(Player p) {
        if (mc.player == null || !p.isBlocking()) return false;
        Vec3 facing = Vec3.directionFromRotation(0.0f, p.getYHeadRot());
        Vec3 fromAttacker = p.position().subtract(mc.player.position());
        Vec3 flat = new Vec3(fromAttacker.x, 0.0, fromAttacker.z).normalize();
        return flat.dot(facing) < 0.0;
    }

    // -------------------------------------------------------------------------
    // Item helpers
    // -------------------------------------------------------------------------

    private static boolean isSword(ItemStack s) { return !s.isEmpty() && s.is(ItemTags.SWORDS); }
    private static boolean isAxe(ItemStack s) { return !s.isEmpty() && s.is(ItemTags.AXES); }
    private static boolean isMace(ItemStack s) { return s.is(Items.MACE); }
    private static boolean isWeapon(ItemStack s) { return isSword(s) || isAxe(s) || isMace(s) || s.is(Items.TRIDENT); }

    private static int level(ItemStack s, ResourceKey<Enchantment> key) {
        for (Holder<Enchantment> e : s.getEnchantments().keySet()) {
            if (e.is(key)) return s.getEnchantments().getLevel(e);
        }
        return 0;
    }

    /** Vanilla base melee damage by item (used for Priority: damage). */
    private static double baseDamage(ItemStack s) {
        Item i = s.getItem();
        if (i == Items.NETHERITE_SWORD) return 8;
        if (i == Items.DIAMOND_SWORD) return 7;
        if (i == Items.IRON_SWORD) return 6;
        if (i == Items.STONE_SWORD) return 5;
        if (i == Items.GOLDEN_SWORD || i == Items.WOODEN_SWORD) return 4;
        if (isSword(s)) return 5;
        if (i == Items.NETHERITE_AXE) return 10;
        if (i == Items.WOODEN_AXE || i == Items.GOLDEN_AXE) return 7;
        if (isAxe(s)) return 9;
        if (isMace(s)) return 6;
        if (i == Items.TRIDENT) return 9;
        return 1;
    }

    /** Vanilla attack speed by item (used for Priority: speed). */
    private static double attackSpeedOf(ItemStack s) {
        Item i = s.getItem();
        if (isSword(s)) return 1.6;
        if (i == Items.WOODEN_AXE || i == Items.STONE_AXE) return 0.8;
        if (i == Items.IRON_AXE) return 0.9;
        if (isAxe(s)) return 1.0;
        if (isMace(s)) return 0.6;
        if (i == Items.TRIDENT) return 1.1;
        return 4.0;
    }

    private void dbg(String msg) {
        if (!debug.value || mc.player == null) return;
        dev.kuro.client.util.DebugChat.say("weapon", msg);
    }
}
