package dev.kuro.client.module.impl;

import dev.kuro.client.util.SwapLimit;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.ForcedKeys;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;

/** Eats the best food from your offhand / hotbar when your hunger drops to the chosen level, then swaps back. */
public class AutoEat extends Module {
    private static AutoEat instance;
    private final Setting.Num hunger = add(new Setting.Num("Eat at hunger", 14, 1, 19, 1));   // food level (20 = full)

    private boolean eating;
    private int ticks, prevSlot = -1;
    private InteractionHand hand = InteractionHand.MAIN_HAND;

    public AutoEat() { super("Auto Eat", Category.PLAYER); instance = this; }

    public static boolean eating() { return instance != null && instance.isEnabled() && instance.eating; }

    @Override protected void onDisable() { stop(); if (prevSlot >= 0 && mc.player != null) SwapLimit.selectNow(prevSlot); prevSlot = -1; }

    private static boolean isFood(ItemStack s) {
        if (s.isEmpty() || s.get(DataComponents.FOOD) == null) return false;
        Item i = s.getItem();
        return i != Items.ROTTEN_FLESH && i != Items.SPIDER_EYE && i != Items.PUFFERFISH && i != Items.POISONOUS_POTATO
                && i != Items.CHORUS_FRUIT && i != Items.GOLDEN_APPLE && i != Items.ENCHANTED_GOLDEN_APPLE;
    }

    private static int nutrition(ItemStack s) {
        FoodProperties f = s.get(DataComponents.FOOD);
        return f == null ? 0 : f.nutrition();
    }

    @Override
    public void onPreTick() {
        Player p = mc.player;
        if (p == null || mc.gameMode == null || mc.screen != null || p.isSpectator() || Freecam.active()) {
            stop();
            return;
        }

        if (eating) {
            ticks++;
            if (p.isUsingItem()) return;                       // still chewing
            if (ticks > 3) { stop(); return; }                 // finished (or got interrupted)
            mc.gameMode.useItem(p, hand);       // not started yet: try again
            return;
        }

        if (prevSlot >= 0) { restoreSlot(); return; }          // one swap per tick: the old slot is still on its way back

        if (p.getFoodData().getFoodLevel() > hunger.intValue()) return;

        if (isFood(p.getOffhandItem())) {
            hand = InteractionHand.OFF_HAND;
        } else {
            int best = -1, bestN = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (isFood(s) && nutrition(s) > bestN) { best = i; bestN = nutrition(s); }
            }
            if (best < 0) return;
            hand = InteractionHand.MAIN_HAND;
            if (best != p.getInventory().getSelectedSlot()) {
                int cur = p.getInventory().getSelectedSlot();
                if (!SwapLimit.select(best)) return;            // one swap per tick: try again next tick
                prevSlot = cur;
            }
        }

        eating = true;
        ticks = 0;
        ForcedKeys.force(mc.options.keyUse);
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(p, hand);
    }

    private void stop() {
        boolean was = eating;
        eating = false;
        ForcedKeys.release(mc.options.keyUse);
        if (was) syncToPhysical(mc.options.keyUse);            // back to what your hand is really doing
        restoreSlot();
    }

    private void restoreSlot() {
        if (prevSlot >= 0 && mc.player != null && !SwapLimit.select(prevSlot)) return;   // retried next tick
        prevSlot = -1;
    }

    /**
     * Sets a key binding to whether its physical key / mouse button is really down. (KeyBinding.updatePressedStates
     * only handles keyboard keys, so a mouse button like right click would stay "held" after we let go.)
     */
    private void syncToPhysical(KeyMapping kb) {
        boolean down = false;
        if (mc.getWindow() != null) {
            long win = mc.getWindow().handle();
            InputConstants.Key k = KeyBindingHelper.getBoundKeyOf(kb);
            if (k.getType() == InputConstants.Type.MOUSE) down = GLFW.glfwGetMouseButton(win, k.getValue()) == GLFW.GLFW_PRESS;
            else if (k.getType() == InputConstants.Type.KEYSYM) down = GLFW.glfwGetKey(win, k.getValue()) == GLFW.GLFW_PRESS;
        }
        kb.setDown(down);
    }
}
