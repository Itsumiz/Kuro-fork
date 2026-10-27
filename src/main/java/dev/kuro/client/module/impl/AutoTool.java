package dev.kuro.client.module.impl;

import dev.kuro.client.util.SwapLimit;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Auto Tool (Player): while you hold the attack button on a block, the fastest tool for that block in your hotbar is
 * selected for you (pickaxe on stone, axe on logs, shovel on dirt, sword on cobwebs...). When you stop mining it can put
 * your previous slot back. It never touches your slot when you are hitting an entity, in a menu, in creative or eating.
 */
public class AutoTool extends Module {
    private final Setting.Bool switchBack = add(new Setting.Bool("Switch back", true));
    private final Setting.Num backDelay = add(new Setting.Num("Switch back delay (ticks)", 4, 0, 20, 1));
    private final Setting.Bool saveDurability = add(new Setting.Bool("Save durability", true));

    private int previous = -1, restoreIn;

    public AutoTool() { super("Auto Tool", Category.PLAYER); }

    @Override protected void onDisable() { if (previous >= 0 && switchBack.value && mc.player != null) SwapLimit.selectNow(previous); previous = -1; }

    @Override
    public void onPreTick() {
        Player p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) { previous = -1; return; }
        boolean blocked = mc.screen != null || p.isCreative() || p.isSpectator() || Freecam.active() || AutoEat.eating();
        BlockHitResult hit = null;
        if (!blocked && mc.options.keyAttack.isDown()
                && mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) hit = b;

        if (hit != null) {
            select(p, mc.level.getBlockState(hit.getBlockPos()), hit);
            restoreIn = backDelay.intValue();
        } else if (previous >= 0 && --restoreIn <= 0) {
            restore();
        }
    }

    private void select(Player p, BlockState st, BlockHitResult hit) {
        if (st.isAir() || st.getDestroySpeed(mc.level, hit.getBlockPos()) < 0) return;        // nothing to mine / unbreakable
        Inventory inv = p.getInventory();
        int cur = inv.getSelectedSlot(), best = cur;
        float bestSpeed = speed(inv.getItem(cur), st);
        for (int i = 0; i < 9; i++) {
            if (i == cur) continue;
            ItemStack s = inv.getItem(i);
            if (saveDurability.value && nearlyBroken(s)) continue;
            float sp = speed(s, st);
            if (sp > bestSpeed + 0.01f) { best = i; bestSpeed = sp; }
        }
        if (best != cur) {
            if (!SwapLimit.select(best)) return;                  // one swap per tick: try again next tick
            if (previous < 0) previous = cur;
        }
    }

    private static float speed(ItemStack s, BlockState st) { return s.isEmpty() ? 1f : s.getDestroySpeed(st); }

    private static boolean nearlyBroken(ItemStack s) { return s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= 2; }

    private void restore() {
        if (previous >= 0 && switchBack.value && mc.player != null && !SwapLimit.select(previous)) return;   // retried next tick
        previous = -1;
    }
}
