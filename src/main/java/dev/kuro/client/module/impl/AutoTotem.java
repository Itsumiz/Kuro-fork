package dev.kuro.client.module.impl;

import dev.kuro.client.util.SwapLimit;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;

/** Keeps a Totem of Undying in your offhand, taking one from your inventory/hotbar whenever it is missing (e.g. after a pop). */
public class AutoTotem extends Module {
    private final Setting.Num delay = add(new Setting.Num("Delay (ticks)", 1, 0, 10, 1));
    private final Setting.Bool replace = add(new Setting.Bool("Replace offhand item", true));   // off = only when the offhand is empty
    private int cooldown;

    public AutoTotem() { super("Auto Totem", Category.COMBAT); }

    @Override
    public void onTick() {
        Player p = mc.player;
        if (p == null || mc.gameMode == null || mc.screen != null || p.isSpectator()) return;
        if (cooldown > 0) { cooldown--; return; }

        var off = p.getOffhandItem();
        if (off.is(Items.TOTEM_OF_UNDYING)) return;
        if (!replace.value && !off.isEmpty()) return;

        for (int i = 0; i < 36; i++) {
            if (!p.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) continue;
            int slot = i < 9 ? 36 + i : i;          // inventory index -> player screen handler slot
            if (!SwapLimit.take()) return;                          // one swap per tick: try again next tick
            // SWAP with button 40 trades the clicked slot with the offhand
            mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, slot, 40, ClickType.SWAP, p);
            cooldown = delay.intValue();
            return;
        }
    }
}
