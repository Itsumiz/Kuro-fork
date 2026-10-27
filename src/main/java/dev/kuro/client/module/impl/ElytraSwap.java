package dev.kuro.client.module.impl;

import dev.kuro.client.util.SwapLimit;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.network.chat.Component;

/**
 * Click it (or press its keybind) to wear the Elytra with the most durability left from your hotbar. Whatever you
 * were wearing on your chest goes back into that hotbar slot. Broken elytras are ignored, and nothing happens if
 * the one you already wear is as good or better. Works with no inventory open. It never stays "on".
 */
public class ElytraSwap extends Module {
    private static final int CHEST_HANDLER_SLOT = 6;

    public ElytraSwap() { super("Elytra Swap", Category.PLAYER); }

    @Override public void toggle() { swap(); }
    @Override public void setEnabled(boolean value) { if (value) swap(); }

    private static int left(ItemStack s) {
        return s.getMaxDamage() - s.getDamageValue();
    }

    private static boolean usable(ItemStack s) {
        return s.is(Items.ELYTRA) && left(s) > 1;               // an elytra with 1 durability left is "broken"
    }

    private void say(String s) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Elytra Swap] " + s), false);
    }

    private void swap() {
        Player p = mc.player;
        if (p == null || mc.gameMode == null || p.isSpectator()) return;
        if (mc.screen != null && !(mc.screen instanceof InventoryScreen)) return;
        if (!p.containerMenu.getCarried().isEmpty()) { say("Put down the item on your cursor first."); return; }

        int best = -1, bestLeft = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (usable(s) && left(s) > bestLeft) { best = i; bestLeft = left(s); }
        }
        if (best < 0) { say("No usable elytra in your hotbar."); return; }

        ItemStack worn = p.getItemBySlot(EquipmentSlot.CHEST);
        if (usable(worn) && left(worn) >= bestLeft) { say("You already wear the best elytra."); return; }

        if (!SwapLimit.take()) { say("Another item swap happened this tick, try again."); return; }   // one swap per tick
        // SWAP with a hotbar button exchanges the armor slot and that hotbar slot in one click
        mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, CHEST_HANDLER_SLOT, best, ClickType.SWAP, p);
        say("Swapped to an elytra with " + bestLeft + " durability left.");
    }
}
