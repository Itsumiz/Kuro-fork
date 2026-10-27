package dev.kuro.client.module.impl;

import dev.kuro.client.util.SwapLimit;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ClickType;

/** When a hotbar stack runs out (last block placed, tool broke...), refills that slot from the same item in your inventory. */
public class AutoReplenish extends Module {
    private final Setting.Num delay = add(new Setting.Num("Delay (ticks)", 1, 1, 10, 1));
    private final Item[] last = new Item[9];
    private int wait;

    public AutoReplenish() { super("Auto Replenish", Category.PLAYER); }

    @Override protected void onEnable() { java.util.Arrays.fill(last, null); wait = 0; }

    @Override
    public void onTick() {
        Player p = mc.player;
        if (p == null || mc.gameMode == null) return;

        boolean screenOpen = mc.screen != null;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (screenOpen || !st.isEmpty()) last[i] = st.isEmpty() ? null : st.getItem();   // only an emptied slot triggers a refill
        }
        if (screenOpen && !(mc.screen instanceof InventoryScreen)) return;
        if (screenOpen) return;                                    // moving items by hand: never fight it
        if (++wait < delay.intValue()) return;

        for (int i = 0; i < 9; i++) {
            if (last[i] == null || !p.getInventory().getItem(i).isEmpty()) continue;
            for (int inv = 9; inv < 36; inv++) {
                ItemStack c = p.getInventory().getItem(inv);
                if (c.isEmpty() || c.getItem() != last[i]) continue;
                if (!SwapLimit.take()) return;                     // one swap per tick: try again next tick
                // SWAP with button i trades that inventory slot with hotbar slot i
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, inv, i, ClickType.SWAP, p);
                last[i] = null;
                wait = 0;
                return;
            }
            last[i] = null;                                        // nothing left to refill with
        }
    }
}
