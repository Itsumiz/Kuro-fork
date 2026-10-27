package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.Pacing;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;

/** Places a cobweb on the nearest player (their feet, optionally their head) using a cobweb from the hotbar. */
public class AutoCobweb extends Module {
    private final Setting.Num range = add(new Setting.Num("Range", 4.5, 2.0, 6.0, 0.5));
    private final Setting.Bool head = add(new Setting.Bool("Head too", false));
    private final Setting.Num finish = add(new Setting.Num("Finish time (s)", 2.0, 0.0, 10.0, 0.5));   // seconds the whole action takes; 0 = old instant behaviour (Delay / Blocks per tick)
    private final Setting.Num delay = add(new Setting.Num("Delay", 2, 1, 10, 1));
    private int wait, restore = -1;

    public AutoCobweb() { super("Auto Cobweb", Category.COMBAT); }

    @Override protected void onEnable() { wait = 0; restore = -1; }
    @Override protected void onDisable() { forceRestore(); }

    private boolean restoreDue;

    private void restoreSlot() {
        if (restore >= 0 && mc.player != null && !BlockPlacer.select(restore)) { restoreDue = true; return; }   // one swap per tick: retried next tick
        restore = -1;
        restoreDue = false;
    }

    private void forceRestore() {
        if (restore >= 0 && mc.player != null) BlockPlacer.selectForce(restore);
        restore = -1;
        restoreDue = false;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.screen != null || Freecam.active()) return;
        if (restoreDue) restoreSlot();

        Player t = BlockPlacer.nearestPlayer(range.value);
        int slot = BlockPlacer.findSlot(s -> s.is(Items.COBWEB));
        if (t == null || slot < 0) { wait = 0; restoreSlot(); return; }

        BlockPos feet = t.blockPosition();
        BlockPos[] spots = head.value ? new BlockPos[]{feet, feet.above()} : new BlockPos[]{feet};
        boolean any = false;
        for (BlockPos p : spots) if (mc.level.getBlockState(p).canBeReplaced()) any = true;
        if (!any) { wait = 0; restoreSlot(); return; }
        if (++wait < Pacing.gap(finish.value, spots.length, delay.intValue())) return;   // paced: the web lands about "Finish time" after the target is in range

        for (BlockPos p : spots) {
            if (!mc.level.getBlockState(p).canBeReplaced()) continue;
            int cur = BlockPlacer.selected();
            if (!BlockPlacer.select(slot)) return;                  // one swap per tick: try again next tick
            if (restore < 0) restore = cur;
            if (BlockPlacer.place(p)) { wait = 0; if (Pacing.paced(finish.value)) restoreSlot(); return; }
        }
        restoreSlot();
    }
}
