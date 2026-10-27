package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra Hop: hop-glide-hop along the ground, with a spear jab just before every landing to keep the speed.
 * Wear an elytra, keep a spear in the hotbar, switch it on and hold W:
 *   jump  ->  release jump for one tick and press it again in the air (that is what starts the glide)  ->  keep jump held
 *   ->  a moment before you touch the ground the spear is selected and jabbed (the lunge pushes you along)  ->  your
 *   old slot comes back  ->  you land and jump again.
 *  - The landing is predicted from the height above the ground and your fall speed. "Jab lead" is how many ticks before
 *    touching down the jab goes out; the spear is taken one tick earlier. Tune it if the jab is early or late.
 *  - The jab is a normal left click (KeyMapping.click) with the spear held, the same as clicking yourself. One slot swap
 *    per tick like every other module.
 *  - A Lunge spear is preferred. Lunge costs hunger, so the jab is skipped below "Min hunger" when the spear has Lunge.
 *  - Optional "Dive pitch": looks down while gliding so you come back to the ground quickly, then levels out to "Jab pitch"
 *    for the jab (the lunge goes where you look). 0 = leave your view alone.
 *  - Stops by itself when the elytra is nearly broken.
 */
public class ElytraHop extends Module {
    private final Setting.Num lead = add(new Setting.Num("Jab lead (ticks)", 2.0, 0.0, 6.0, 0.5));
    private final Setting.Num minHunger = add(new Setting.Num("Min hunger (Lunge)", 6, 0, 20, 1));
    private final Setting.Num dive = add(new Setting.Num("Dive pitch (0 = off)", 0, 0, 60, 5));
    private final Setting.Num jabPitch = add(new Setting.Num("Jab pitch", 5, -30, 30, 5));
    private final Setting.Bool needForward = add(new Setting.Bool("Only while holding W", true));

    private boolean pressedByUs, released, armed, jabbed, warned;
    private int restoreSlot = -1, restoreIn;

    public ElytraHop() { super("Elytra Hop", Category.MOVEMENT); }

    @Override
    protected void onEnable() { resetAll(); warned = false; }

    @Override
    protected void onDisable() {
        if (restoreSlot >= 0 && mc.player != null) BlockPlacer.selectForce(restoreSlot);
        releaseJump();
        resetAll();
    }

    private void resetAll() {
        released = false; armed = false; jabbed = false;
        restoreSlot = -1; restoreIn = 0;
    }

    private void releaseJump() {
        if (pressedByUs) { pressedByUs = false; KeyMapping.setAll(); }   // re-sync with the real key
    }

    private void holdJump(boolean down) {
        mc.options.keyJump.setDown(down);
        pressedByUs = down || pressedByUs;
    }

    // ---------------- the loop (start of the tick, where key presses are handled) ----------------

    @Override
    public void onPreTick() {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null || mc.screen != null || Freecam.active()) { releaseJump(); return; }

        handleRestore(p);

        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || chest.getMaxDamage() - chest.getDamageValue() <= 2) {
            releaseJump();
            if (!warned) { warned = true; Notifications.info("Elytra Hop", "Wear an elytra with durability left", false); }
            return;
        }
        if (needForward.value && !mc.options.keyUp.isDown()) { releaseJump(); return; }
        if (p.isInWater() || p.isInLava() || p.onClimbable() || p.isShiftKeyDown()) { releaseJump(); return; }

        if (p.onGround()) {
            // landed (or standing): hop again
            if (armed || jabbed) finishJab(p);
            armed = false; jabbed = false; released = false;
            holdJump(true);
            return;
        }

        if (!p.isFallFlying()) {
            // in the air after the hop and not gliding yet: a glide needs a fresh jump press
            if (!released) { holdJump(false); released = true; }
            else holdJump(true);
            return;
        }

        holdJump(true);                                   // gliding: keep jump held until the ground
        glideStep(p);
    }

    private void glideStep(LocalPlayer p) {
        Vec3 v = p.getDeltaMovement();
        double h = heightAboveGround(p);
        double t = v.y < -0.02 ? h / -v.y : 99.0;         // ticks until touchdown at the current fall speed

        boolean near = t <= lead.value + 1.0;
        if (dive.value > 0) {
            float want = near ? (float) jabPitch.value : (float) dive.value;
            p.setXRot(Mth.clamp(Mth.lerp(0.35f, p.getXRot(), want), -90f, 90f));
        }
        if (jabbed) return;

        if (near && !armed) { arm(p); return; }
        if (armed && t <= lead.value) jab(p);
    }

    /** Takes the spear into the hand (one swap per tick). */
    private void arm(LocalPlayer p) {
        ItemStack held = p.getMainHandItem();
        if (SpearSwap.isSpear(held)) { armed = true; return; }
        int slot = findSpear();
        if (slot < 0) {
            if (!warned) { warned = true; Notifications.info("Elytra Hop", "No spear in your hotbar", false); }
            jabbed = true;                                // nothing to jab with: skip it for this hop
            return;
        }
        int cur = BlockPlacer.selected();
        if (!BlockPlacer.select(slot)) return;            // refused this tick: try again next tick
        restoreSlot = cur;
        armed = true;
    }

    private void jab(LocalPlayer p) {
        ItemStack held = p.getMainHandItem();
        if (!SpearSwap.isSpear(held)) { armed = false; return; }     // the swap did not stick: arm again
        if (hasLunge(held) && p.getFoodData().getFoodLevel() < minHunger.intValue()) {
            if (!warned) { warned = true; Notifications.info("Elytra Hop", "Too hungry for the lunge", false); }
            jabbed = true;
            restoreIn = 1;
            return;
        }
        KeyMapping.click(KeyBindingHelper.getBoundKeyOf(mc.options.keyAttack));   // a real left click, handled later this tick
        jabbed = true;
        restoreIn = 1;                                    // slot goes back next tick, after the click was processed
    }

    private void handleRestore(LocalPlayer p) {
        if (restoreSlot < 0) return;
        if (!jabbed && !p.onGround()) return;             // still waiting for the jab
        if (restoreIn > 0) { restoreIn--; if (restoreIn > 0) return; }
        if (BlockPlacer.select(restoreSlot)) restoreSlot = -1;   // refused: retried next tick
    }

    private void finishJab(LocalPlayer p) {
        restoreIn = 0;
        if (restoreSlot >= 0 && BlockPlacer.select(restoreSlot)) restoreSlot = -1;
    }

    // ---------------- helpers ----------------

    /** Distance from your feet straight down to the first solid block (99 when there is none within 12 blocks). */
    private double heightAboveGround(LocalPlayer p) {
        Vec3 from = p.position();
        Vec3 to = from.add(0, -12, 0);
        BlockHitResult r = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (r.getType() == HitResult.Type.MISS) return 99.0;
        return Math.max(0.0, from.y - r.getLocation().y);
    }

    private int findSpear() {
        int slot = BlockPlacer.findSlot(s -> SpearSwap.isSpear(s) && hasLunge(s));
        return slot >= 0 ? slot : BlockPlacer.findSlot(SpearSwap::isSpear);
    }

    private static boolean hasLunge(ItemStack s) {
        for (Holder<Enchantment> e : s.getEnchantments().keySet())
            if (e.getRegisteredName().endsWith("lunge")) return true;
        return false;
    }
}
