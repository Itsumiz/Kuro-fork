package dev.kuro.client.module.impl;

import dev.kuro.client.mixin.MinecraftClientAccessor;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/** Shorter right-click repeat delay (vanilla is 4 ticks). */
public class FastPlace extends Module {
    private final Setting.Num delay = add(new Setting.Num("Delay (ticks)", 0, 0, 4, 1));
    private final Setting.Bool blocksOnly = add(new Setting.Bool("Blocks only", true));

    public FastPlace() { super("Fast Place", Category.PLAYER); }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.screen != null) return;
        if (blocksOnly.value) {
            ItemStack held = mc.player.getMainHandItem();
            if (!(held.getItem() instanceof BlockItem)) return;
        }
        MinecraftClientAccessor acc = (MinecraftClientAccessor) mc;
        if (acc.kuro$getItemUseCooldown() > delay.intValue()) acc.kuro$setItemUseCooldown(delay.intValue());
    }
}
