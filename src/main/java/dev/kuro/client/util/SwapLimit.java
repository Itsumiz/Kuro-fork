package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;

/**
 * One item swap per client tick, for every module together. A "swap" is a hotbar slot change (Auto Weapon, Auto
 * Tool, Scaffold, Auto Eat...) or an inventory swap (Auto Totem, Auto Armor, Auto Replenish, Elytra Swap, Inv Sorter).
 * Selecting the slot that is already held is not a swap and is always allowed. A module that is refused simply tries
 * again next tick (and keeps the slot it has to go back to until it manages to).
 */
public final class SwapLimit {
    private static int tick, last = -1;

    private SwapLimit() {}

    /** Called once at the start of every client tick. */
    public static void nextTick() { tick++; }

    /** Claims this tick's swap. False when a swap was already made this tick. */
    public static boolean take() {
        if (last == tick) return false;
        last = tick;
        return true;
    }

    /** Selects the hotbar slot (one swap per tick). True when the slot is held afterwards, false when refused. */
    public static boolean select(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        Inventory inv = mc.player.getInventory();
        if (inv.getSelectedSlot() == slot) return true;
        if (!take()) return false;
        inv.setSelectedSlot(slot);
        return true;
    }

    /** Clean-up when a module is switched off: selects the slot even if a swap was already made this tick. */
    public static void selectNow(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.getInventory().getSelectedSlot() == slot) return;
        last = tick;
        mc.player.getInventory().setSelectedSlot(slot);
    }
}
