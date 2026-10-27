package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.ClickType;

import java.util.HashSet;
import java.util.Set;

/** While a chest / barrel-style container or shulker box is open, shift-clicks everything out into your inventory. */
public class ChestStealer extends Module {
    private final Setting.Num delay = add(new Setting.Num("Delay (ticks)", 1, 0, 10, 1));
    private final Setting.Bool autoClose = add(new Setting.Bool("Auto close", false));
    private final Set<Integer> tried = new HashSet<>();
    private int lastSync = -1, cooldown;

    public ChestStealer() { super("Chest Stealer", Category.PLAYER); }

    @Override protected void onDisable() { tried.clear(); lastSync = -1; }

    @Override
    public void onTick() {
        if (mc.player == null || mc.gameMode == null) return;
        if (!(mc.screen instanceof AbstractContainerScreen<?> hs)) { tried.clear(); lastSync = -1; return; }

        AbstractContainerMenu h = hs.getMenu();
        int size;
        if (h instanceof ChestMenu g) size = g.getRowCount() * 9;
        else if (h instanceof ShulkerBoxMenu) size = 27;
        else return;

        if (h.containerId != lastSync) { tried.clear(); lastSync = h.containerId; cooldown = delay.intValue(); }
        if (cooldown > 0) { cooldown--; return; }

        for (int i = 0; i < size; i++) {
            if (tried.contains(i)) continue;
            tried.add(i);                                  // each slot is tried once, so a full inventory can't loop forever
            if (h.getSlot(i).getItem().isEmpty()) continue;
            mc.gameMode.handleInventoryMouseClick(h.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
            cooldown = delay.intValue();
            return;
        }
        if (autoClose.value) mc.player.closeContainer();
    }
}
