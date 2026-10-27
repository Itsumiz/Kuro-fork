package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Holder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;

/**
 * Breach swap: the moment you attack an entity while holding something else (a sword by default), the
 * hit is made with a Breach mace from your hotbar instead, then your original slot is selected again.
 * Works for your own clicks and for Kill Aura / Trigger Bot hits too.
 */
public class BreachSwap extends Module {
    private final Setting.Bool breachOnly = add(new Setting.Bool("Breach only", true));   // off = any mace
    private final Setting.Bool fromSword = add(new Setting.Bool("Only from sword", true));
    private final Setting.Num delay = add(new Setting.Num("Swap back", 2, 1, 10, 1));    // ticks

    private int previous = -1, restoreIn;

    public BreachSwap() {
        super("Breach Swap", Category.COMBAT);
        // runs just before an attack is sent, so the slot change is synced to the server first
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (isEnabled() && world.isClientSide() && player == mc.player) swapToMace();
            return InteractionResult.PASS;
        });
    }

    @Override protected void onDisable() { if (previous >= 0 && mc.player != null) BlockPlacer.selectForce(previous); previous = -1; }

    private void swapToMace() {
        if (mc.player == null || previous >= 0) return;                // already swapped for this hit
        ItemStack held = mc.player.getMainHandItem();
        if (held.is(Items.MACE)) return;
        if (fromSword.value && !held.is(ItemTags.SWORDS)) return;

        int slot = BlockPlacer.findSlot(s -> s.is(Items.MACE) && hasBreach(s));
        if (slot < 0 && !breachOnly.value) slot = BlockPlacer.findSlot(s -> s.is(Items.MACE));
        if (slot < 0) return;

        int cur = BlockPlacer.selected();
        if (!BlockPlacer.select(slot)) return;                          // one swap per tick: this hit goes out with what is held
        previous = cur;
        restoreIn = delay.intValue();
    }

    private static boolean hasBreach(ItemStack s) {
        for (Holder<Enchantment> e : s.getEnchantments().keySet()) {
            if (e.is(Enchantments.BREACH)) return true;
        }
        return false;
    }

    @Override
    public void onTick() {
        if (previous >= 0 && --restoreIn <= 0) restoreNow();
    }

    private void restoreNow() {
        if (previous >= 0 && mc.player != null && !BlockPlacer.select(previous)) return;   // retried next tick
        previous = -1;
    }
}
