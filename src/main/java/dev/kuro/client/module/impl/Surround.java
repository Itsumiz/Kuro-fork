package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.Pacing;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Walls yourself in: a block on each of the four sides of your feet (optionally the floor under you and a second
 * ring at head height). Prefers obsidian, otherwise any plain solid hotbar block. Anything that gets broken is put
 * back. Counterpart of Auto Box, which walls in another player.
 */
public class Surround extends Module {
    private static final Direction[] SIDES = { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };

    private final Setting.Num perTick = add(new Setting.Num("Blocks/tick", 2, 1, 4, 1));
    private final Setting.Num delay = add(new Setting.Num("Delay", 1, 1, 10, 1));
    private final Setting.Num finish = add(new Setting.Num("Finish time (s)", 2.0, 0.0, 10.0, 0.5));   // seconds the whole action takes; 0 = old instant behaviour (Delay / Blocks per tick)
    private final Setting.Bool head = add(new Setting.Bool("Head level too", false));
    private final Setting.Bool floor = add(new Setting.Bool("Floor under feet", false));
    private final Setting.Bool onGround = add(new Setting.Bool("Only on ground", true));
    private final Setting.Bool stayPut = add(new Setting.Bool("Off if I move", true));
    private final Setting.Bool obsidianOnly = add(new Setting.Bool("Obsidian only", false));

    private int wait, restore = -1;
    private BlockPos anchor;

    public Surround() { super("Surround", Category.COMBAT); }

    @Override
    protected void onEnable() {
        wait = 0;
        restore = -1;
        anchor = mc.player != null ? mc.player.blockPosition() : null;
    }

    @Override protected void onDisable() { forceRestore(); anchor = null; }

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
        BlockPos feet = mc.player.blockPosition();
        if (anchor == null) anchor = feet;
        if (!feet.equals(anchor)) {
            if (stayPut.value) { setEnabled(false); return; }
            anchor = feet;                                           // follow the player
        }
        if (onGround.value && !mc.player.onGround()) return;

        int slot = BlockPlacer.findSlot(s -> s.is(Items.OBSIDIAN));
        if (slot < 0 && !obsidianOnly.value) slot = BlockPlacer.findSlot(BlockPlacer::isSolidBlock);
        if (slot < 0) { restoreSlot(); return; }

        // floor first, then the ring around the feet, then the ring at head height, so every block has support
        List<BlockPos> spots = new ArrayList<>();
        if (floor.value) spots.add(feet.below());
        for (Direction d : SIDES) spots.add(feet.relative(d));
        if (head.value) for (Direction d : SIDES) spots.add(feet.above().relative(d));

        List<BlockPos> need = new ArrayList<>();
        for (BlockPos p : spots) {
            if (!mc.level.getBlockState(p).canBeReplaced()) continue;                       // already filled
            AABB b = new AABB(p);
            if (!mc.level.getEntities((net.minecraft.world.entity.Entity) null, b, e -> e instanceof LivingEntity && !e.isSpectator()).isEmpty()) continue;   // you / a mob is in the way
            need.add(p);
        }
        if (need.isEmpty()) { wait = 0; restoreSlot(); return; }                            // nothing to do: the next job starts a fresh timer
        if (++wait < Pacing.gap(finish.value, spots.size(), delay.intValue())) return;      // paced: one block every finish/N seconds

        int per = Pacing.perStep(finish.value, perTick.intValue());
        int placed = 0;
        for (BlockPos p : need) {
            int cur = BlockPlacer.selected();
            if (!BlockPlacer.select(slot)) return;                  // one swap per tick: try again next tick
            if (restore < 0) restore = cur;
            if (BlockPlacer.place(p) && ++placed >= per) break;
        }
        if (placed > 0) { wait = 0; if (Pacing.paced(finish.value)) restoreSlot(); } else restoreSlot();
    }
}
