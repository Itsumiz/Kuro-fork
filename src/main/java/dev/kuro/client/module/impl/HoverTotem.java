package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

/**
 * In the survival inventory, hovering a totem briefly moves it to offhand.
 * Simplified port of Respect HoverTotem.
 */
public class HoverTotem extends Module {
    private final Setting.Num delay = add(new Setting.Num("Hover ticks", 2, 0, 10, 1));
    private final Setting.Bool onlyEmpty = add(new Setting.Bool("Only empty offhand", true));

    private int hoverTicks;
    private int lastSlot = -1;

    public HoverTotem() { super("Hover Totem", Category.PLAYER); }

    @Override
    public void onTick() {
        if (mc.player == null || mc.gameMode == null) return;
        if (!(mc.screen instanceof InventoryScreen)) { hoverTicks = 0; lastSlot = -1; return; }
        if (onlyEmpty.value && !mc.player.getOffhandItem().isEmpty()
                && !mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;

        // focused slot via menu - approximate: scan hotbar+main for hovered totem using container
        var menu = mc.player.containerMenu;
        int hovered = -1;
        // InventoryScreen doesn't expose hovered slot publicly on all mappings; use carried empty + last clicked is weak.
        // Fallback: if player is not carrying and a totem is under cursor — skip if we can't see hover.
        // Use offhand quick-move when mouse is over a totem slot by checking slot under mouse from screen.
        try {
            var f = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
            f.setAccessible(true);
            Object slotObj = f.get(mc.screen);
            if (slotObj instanceof net.minecraft.world.inventory.Slot slot) {
                if (slot.getItem().is(Items.TOTEM_OF_UNDYING) && slot.index != 45) {
                    hovered = slot.index;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            return;
        }

        if (hovered < 0) { hoverTicks = 0; lastSlot = -1; return; }
        if (hovered != lastSlot) { lastSlot = hovered; hoverTicks = 0; }
        hoverTicks++;
        if (hoverTicks < delay.intValue()) return;

        // 40 is offhand in player inventory menu indices vary; use QUICK_MOVE to offhand via swap with 40
        int offhand = 45; // player container offhand slot index in InventoryMenu
        mc.gameMode.handleInventoryMouseClick(menu.containerId, hovered, 40, ClickType.SWAP, mc.player);
        hoverTicks = 0;
    }
}
