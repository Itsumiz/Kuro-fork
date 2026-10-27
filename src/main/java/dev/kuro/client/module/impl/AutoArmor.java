package dev.kuro.client.module.impl;

import dev.kuro.client.util.SwapLimit;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;

/**
 * Wears the best armor you are carrying: fills empty armor slots and upgrades worse pieces
 * (netherite > diamond > iron > chain/copper > gold/turtle > leather, then most durability left).
 * Never swaps out an Elytra.
 */
public class AutoArmor extends Module {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final int[] HANDLER_SLOT = {5, 6, 7, 8};
    private static final String[] SUFFIX = {"_helmet", "_chestplate", "_leggings", "_boots"};

    private final Setting.Num delay = add(new Setting.Num("Delay (ticks)", 2, 1, 10, 1));
    private int wait;

    public AutoArmor() { super("Auto Armor", Category.COMBAT); }

    private static int score(ItemStack s, int part) {
        if (s.isEmpty()) return -1;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        if (!id.endsWith(SUFFIX[part])) return -1;
        int mat = id.contains("netherite") ? 6 : id.contains("diamond") ? 5 : id.contains("iron") ? 4
                : id.contains("chainmail") || id.contains("copper") ? 3 : id.contains("gold") || id.contains("turtle") ? 2 : 1;
        int left = s.isDamageableItem() ? Math.max(0, s.getMaxDamage() - s.getDamageValue()) : 0;
        return mat * 10000 + Math.min(9999, left);
    }

    @Override
    public void onTick() {
        Player p = mc.player;
        if (p == null || mc.gameMode == null || p.isSpectator()) return;
        if (mc.screen != null && !(mc.screen instanceof InventoryScreen)) return;
        if (!p.containerMenu.getCarried().isEmpty()) return;
        if (++wait < delay.intValue()) return;

        for (int part = 0; part < 4; part++) {
            ItemStack cur = p.getItemBySlot(SLOTS[part]);
            if (part == 1 && cur.is(Items.ELYTRA)) continue;
            int best = -1, bestScore = score(cur, part);
            for (int i = 0; i < 36; i++) {
                int sc = score(p.getInventory().getItem(i), part);
                if (sc > bestScore) { best = i; bestScore = sc; }
            }
            if (best < 0) continue;

            if (!SwapLimit.take()) return;                         // one swap per tick: try again next tick
            int sync = p.inventoryMenu.containerId;
            int from = best < 9 ? 36 + best : best;                // inventory index -> player screen handler slot
            if (cur.isEmpty()) {
                mc.gameMode.handleInventoryMouseClick(sync, from, 0, ClickType.QUICK_MOVE, p);   // shift-click equips
            } else {
                mc.gameMode.handleInventoryMouseClick(sync, from, 0, ClickType.PICKUP, p);
                mc.gameMode.handleInventoryMouseClick(sync, HANDLER_SLOT[part], 0, ClickType.PICKUP, p);
                mc.gameMode.handleInventoryMouseClick(sync, from, 0, ClickType.PICKUP, p);
            }
            wait = 0;
            return;
        }
    }
}
