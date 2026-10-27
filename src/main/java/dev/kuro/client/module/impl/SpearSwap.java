package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionResult;

/**
 * Spear swap (attribute swap): the instant you attack, the spear from your hotbar is selected so the jab / lunge is
 * made with it, then your original slot comes back. That way you keep holding something else (sword, totem, rocket...)
 * and never have to wait for the spear to be raised / wound up before you dash.
 * Works on your own left clicks (even in the air, for the dash) and on Kill Aura / Trigger Bot hits.
 */
public class SpearSwap extends Module {
    private final Setting.Bool lungeOnly = add(new Setting.Bool("Lunge spears only", false));
    private final Setting.Bool inAir = add(new Setting.Bool("Swap on air clicks", true));   // needed for the dash
    private final Setting.Bool fromAny = add(new Setting.Bool("From any item", true));      // off = only from a sword
    private final Setting.Num delay = add(new Setting.Num("Swap back", 2, 1, 10, 1));      // ticks after the click ends

    private int previous = -1, restoreIn;

    public SpearSwap() {
        super("Spear Swap", Category.COMBAT);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (isEnabled() && world.isClientSide() && player == mc.player) swapToSpear();
            return InteractionResult.PASS;
        });
    }

    @Override public boolean early() { return true; }       // switch slots before the click is processed this tick
    @Override protected void onDisable() { if (previous >= 0 && mc.player != null) BlockPlacer.selectForce(previous); previous = -1; }

    static boolean isSpear(ItemStack s) {
        return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_spear");
    }

    private static boolean hasLunge(ItemStack s) {
        for (Holder<Enchantment> e : s.getEnchantments().keySet())
            if (e.getRegisteredName().endsWith("lunge")) return true;
        return false;
    }

    /** Best spear in the hotbar: a lunge one first, then any (later slots = usually the better tier is chosen by order of tier). */
    private int findSpear() {
        int slot = BlockPlacer.findSlot(s -> isSpear(s) && hasLunge(s));
        if (slot < 0 && !lungeOnly.value) slot = BlockPlacer.findSlot(SpearSwap::isSpear);
        return slot;
    }

    private void swapToSpear() {
        if (mc.player == null || previous >= 0) { if (previous >= 0) restoreIn = delay.intValue(); return; }
        ItemStack held = mc.player.getMainHandItem();
        if (isSpear(held)) return;                                          // already holding one
        if (!fromAny.value && !held.is(net.minecraft.tags.ItemTags.SWORDS)) return;
        int slot = findSpear();
        if (slot < 0) return;
        int cur = BlockPlacer.selected();
        if (!BlockPlacer.select(slot)) return;                          // one swap per tick: this hit goes out with what is held
        previous = cur;
        restoreIn = delay.intValue();
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) return;
        boolean mining = mc.hitResult != null && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
        boolean clicking = mc.screen == null && !mining && mc.options.keyAttack.isDown();   // never swap tools while mining
        if (clicking && inAir.value) { swapToSpear(); if (previous >= 0) restoreIn = delay.intValue(); }
        else if (previous >= 0 && --restoreIn <= 0) restoreNow();
    }

    private void restoreNow() {
        if (previous >= 0 && mc.player != null && !BlockPlacer.select(previous)) return;   // retried next tick
        previous = -1;
    }
}
