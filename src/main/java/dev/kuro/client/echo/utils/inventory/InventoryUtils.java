package dev.kuro.client.echo.utils.inventory;

import dev.kuro.client.echo.handlers.InputHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

import static dev.kuro.client.echo.Imports.mc;

/** The hotbar helpers of hack.echo.client.utils.inventory.InventoryUtils that the anchor code uses. */
public final class InventoryUtils {
    private InventoryUtils() {}

    public static void setInvSlot(int slot) {
        if (mc.player.getInventory().getSelectedSlot() == slot) return;
        mc.player.getInventory().setSelectedSlot(slot);
    }

    public static void setInvSlot(int slot, boolean inputSimulation) {
        if (slot < 0 || slot > 8) return;
        if (mc.player.getInventory().getSelectedSlot() == slot) return;

        if (inputSimulation) {
            if (mc.options != null && mc.options.keyHotbarSlots != null) {
                KeyMapping hotbarKey = mc.options.keyHotbarSlots[slot];
                if (hotbarKey != null) InputHandler.incrementClickCountForKeyMapping(hotbarKey);
            }
        } else {
            setInvSlot(slot);
        }
    }

    public static int findItemWithPredicateInHotbar(Predicate<ItemStack> predicate) {
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i <= 8; i++) {
            if (predicate.test(inventory.getItem(i))) return i;
        }
        return -1;
    }
}
