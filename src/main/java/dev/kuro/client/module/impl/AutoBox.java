package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.mixin.InteractionManagerInvoker;
import dev.kuro.client.util.Pacing;
import dev.kuro.client.util.ServerSync;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Auto Box: traps the nearest player.
 *
 *   1. COBWEB  - a cobweb goes into their feet block (needs a cobweb in the hotbar; without one this step is skipped).
 *   2. BOX     - once they are stuck in the web, blocks are built around them: a ring at their feet, a ring at head height
 *                and a roof (a block on one side first, because a roof block needs something to be placed against).
 *                Obsidian is preferred, otherwise any plain solid hotbar block.
 *
 * One spot is deliberately left EMPTY (setting "Opening") so you can still hit them: by default the head-height block on
 * the side that faces you. The side is chosen when the target is picked and then kept, so the gap does not jump around as
 * you move. Every block that is still to be placed is outlined in the world ("Highlights"); the opening is outlined in its
 * own colour. If the web is broken before the box is done, a new web goes down first.
 *
 * Vanilla behaviour: every placement is one ordinary right-click, taken at the START of the client tick (before that tick's
 * movement packet). Rotation "Camera" (default): the real view turns toward the spot like Silent Aura (a fraction of the error per tick,
 * snapped to the mouse step); the click goes out on a later tick, once the angles from the last movement packet really point at a face
 * next to the spot (slot packet, use-item-on packet, swing packet). Rotation "Silent": BlockPlacer.placeAt sends slot packet, look packet,
 * use-item-on packet, swing packet, in that order. The click is a
 * real one (a visible face you could reach, the aim snapped to the mouse step, no entity in the way, nothing placed inside an entity
 * or outside the world). One interaction step per tick, each waiting for the server's last transaction (ServerSync); the slot swap
 * back is its own step and happens once the box is done, not between blocks. Spots that cannot be clicked from where you stand
 * are skipped for a short while instead of being retried every tick.
 *
 * "Front side only": the plan is cut down to the blocks a real click could place from where you stand right now (BlockPlacer.reachable:
 * a visible face of a neighbouring block, inside reach, no entity in the way). The far side of the target, hidden behind its body,
 * is never tried, never outlined and never waited for; the roof starts from a side wall you can see. A spot that becomes clickable
 * later (you move, or a neighbouring block appears) joins the plan then.
 */
public class AutoBox extends Module {
    private static final Direction[] SIDES = { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };

    private final Setting.Num range = add(new Setting.Num("Range", 5.0, 2.0, 6.0, 0.5));
    private final Setting.Bool webFirst = add(new Setting.Bool("Cobweb first", true));
    private final Setting.Num opening = add(new Setting.Num("Opening", 1, 0, 3, 1).labelled("None (full box)", "Head, facing you", "Feet, facing you", "Top"));
    private final Setting.Bool cap = add(new Setting.Bool("Roof", true));
    /** Only blocks a real click could place from where you stand (the side of the target that faces you); spots hidden behind them are skipped. */
    private final Setting.Bool frontOnly = add(new Setting.Bool("Front side only", false));
    private final Setting.Num rotation = add(new Setting.Num("Rotation", 0, 0, 1, 1).labelled("Camera (turns your view)", "Silent (look packet)"));
    private final Setting.Num aimSpeed = add(new Setting.Num("Aim speed", 6.0, 1.0, 10.0, 0.5));
    private final Setting.Num finish = add(new Setting.Num("Finish time (s)", 2.0, 0.0, 10.0, 0.5));   // seconds the whole action takes; 0 = use "Delay" (ticks between blocks)
    private final Setting.Num delay = add(new Setting.Num("Delay", 1, 1, 10, 1));
    private final Setting.Bool highlights = add(new Setting.Bool("Highlights", true));
    private final Setting.Hue color = add(new Setting.Hue("Block color", 0.55f).wheel());
    private final Setting.Hue openColor = add(new Setting.Hue("Opening color", 0.38f).wheel());
    private final Setting.Hue webColor = add(new Setting.Hue("Cobweb color", 0.14f).wheel());
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 2, 1, 3, 1));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));

    private int wait, restore = -1, webWait, ticks;
    private final Map<Long, Integer> skipUntil = new HashMap<>();   // spots that could not be clicked: not retried for a few ticks
    private UUID lockedTarget;
    private Direction towardMe = Direction.NORTH;        // side of the target that faces you, fixed per target

    // what the world renderer shows (set every tick)
    private final List<BlockPos> shownBlocks = new ArrayList<>();
    private BlockPos shownOpening, shownWeb;

    public AutoBox() { super("Auto Box", Category.COMBAT); }

    @Override protected void onEnable() { wait = 0; restore = -1; webWait = 0; ticks = 0; skipUntil.clear(); lockedTarget = null; clearShown(); }
    @Override protected void onDisable() { forceRestore(); clearShown(); lockedTarget = null; }

    private void clearShown() { shownBlocks.clear(); shownOpening = null; shownWeb = null; }

    /** Swaps back to the slot held before: its own interaction step (one per tick, after the server confirmed the last one). */
    private void tryRestore() {
        if (restore < 0 || mc.player == null) return;
        if (!ServerSync.ready(5)) return;
        if (BlockPlacer.select(restore)) {
            ServerSync.beginStep();
            if (mc.gameMode != null) ((InteractionManagerInvoker) mc.gameMode).kuro$syncSelectedSlot();
            restore = -1;
        }
    }

    private void forceRestore() {
        if (restore >= 0 && mc.player != null) {
            BlockPlacer.selectForce(restore);
            if (mc.gameMode != null) ((InteractionManagerInvoker) mc.gameMode).kuro$syncSelectedSlot();
        }
        restore = -1;
    }

    private static boolean webbed(BlockPos feet) {
        var level = mc.level;
        return level.getBlockState(feet).is(Blocks.COBWEB) || level.getBlockState(feet.above()).is(Blocks.COBWEB);
    }

    /** Side of the target that faces the player (dominant axis). */
    private Direction facing(Player t) {
        double dx = mc.player.getX() - t.getX(), dz = mc.player.getZ() - t.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** Runs at the start of the client tick, where a vanilla client handles right-clicks (before the movement packet). */
    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.screen != null || Freecam.active()) { clearShown(); return; }
        ticks++;
        skipUntil.values().removeIf(until -> until <= ticks);
        if (webWait > 0) webWait--;

        Player t = BlockPlacer.nearestPlayer(range.value);
        if (t == null) { wait = 0; webWait = 0; lockedTarget = null; clearShown(); tryRestore(); return; }
        if (!t.getUUID().equals(lockedTarget)) { lockedTarget = t.getUUID(); towardMe = facing(t); }

        int blockSlot = BlockPlacer.findSlot(s -> s.is(Items.OBSIDIAN));
        if (blockSlot < 0) blockSlot = BlockPlacer.findSlot(BlockPlacer::isSolidBlock);
        int webSlot = webFirst.value ? BlockPlacer.findSlot(s -> s.is(Items.COBWEB)) : -1;

        BlockPos feet = t.blockPosition();
        AABB targetBox = t.getBoundingBox(), myBox = mc.player.getBoundingBox();

        // ---- the plan: ring at feet, ring at head, then the roof (side block first, then the middle) ----
        int open = opening.intValue();
        BlockPos openSpot = open == 1 ? feet.above().relative(towardMe) : open == 2 ? feet.relative(towardMe) : null;
        List<BlockPos> spots = new ArrayList<>();
        for (int y = 0; y <= 1; y++) for (Direction d : SIDES) spots.add(feet.above(y).relative(d));
        if (openSpot != null) spots.remove(openSpot);
        if (cap.value && open != 3) {
            Direction scaffold = towardMe.getOpposite();                      // away from the gap, so its wall block exists
            if (frontOnly.value) scaffold = towardMe.getClockWise();          // front only: a side wall you can see, not the far one
            else if (open == 0) scaffold = SIDES[0];
            spots.add(feet.above(2).relative(scaffold));
            spots.add(feet.above(2));
        }
        List<BlockPos> need = new ArrayList<>();
        for (BlockPos p : spots) {
            if (!mc.level.getBlockState(p).canBeReplaced()) continue;         // already filled
            AABB b = new AABB(p);
            if (b.intersects(targetBox) || b.intersects(myBox)) continue;     // would be rejected / trap yourself
            if (frontOnly.value && !BlockPlacer.reachable(p)) continue;       // no click from where you stand could place it
            need.add(p);
        }

        boolean stuck = webbed(feet);
        boolean needWeb = webSlot >= 0 && !stuck && mc.level.getBlockState(feet).canBeReplaced();

        // ---- what to show in the world ----
        shownBlocks.clear();
        if (!need.isEmpty() && blockSlot >= 0) shownBlocks.addAll(need);
        shownOpening = openSpot != null && mc.level.getBlockState(openSpot).canBeReplaced() ? openSpot : null;
        shownWeb = needWeb ? feet : null;

        // ---- act: at most one interaction step this tick ----
        List<BlockPos> candidates = needWeb ? List.of(feet) : need;
        int slot = needWeb ? webSlot : blockSlot;
        if (slot < 0 || candidates.isEmpty()) { wait = 0; tryRestore(); return; }   // nothing left to do: swap back
        if (needWeb && webWait > 0) return;                                        // web placed, waiting for the server to show it
        if (++wait < Pacing.gap(finish.value, need.size() + (needWeb ? 1 : 0), delay.intValue())) return;
        if (!ServerSync.ready(5)) return;                                          // previous step not confirmed yet / server moved us this tick

        for (BlockPos p : candidates) {
            if (skipUntil.containsKey(p.asLong())) continue;
            int before = BlockPlacer.selected();
            BlockPlacer.PlaceResult r;
            if (rotation.intValue() == 0) {
                // camera: turn the real view toward the spot (like Silent Aura); the click goes out on a LATER tick, once the server
                // has the angles from this tick's movement packet and the ray from them really hits a face next to the spot
                BlockPlacer.Attempt at = BlockPlacer.placeFromView(this, slot, p);
                r = at.result();
                if (r == BlockPlacer.PlaceResult.AIM) {
                    BlockPlacer.turnToward(at.yaw(), at.pitch(), aimSpeed.value);
                    return;
                }
            } else {
                r = BlockPlacer.placeAt(this, slot, p);
            }
            if (BlockPlacer.selected() != before && restore < 0) restore = before;
            if (r == BlockPlacer.PlaceResult.DONE) {
                ServerSync.beginStep();
                wait = 0;
                if (needWeb) webWait = 8;
                return;
            }
            if (r == BlockPlacer.PlaceResult.LATER) return;                        // a rule said "not now": same spot again next tick
            skipUntil.put(p.asLong(), ticks + 10);                                 // no valid click on this spot: try the next one
        }
    }

    /** Outlines the blocks that are still to be placed, the cobweb spot and the opening that is left free. */
    @Override
    public void onWorld() {
        if (!highlights.value || mc.level == null) return;
        int t = thickness.intValue();
        int b = color.rgb(), o = openColor.rgb(), w = webColor.rgb();
        double in = 0.03;
        for (BlockPos p : shownBlocks)
            WorldGfx.box(p.getX() + in, p.getY() + in, p.getZ() + in, p.getX() + 1 - in, p.getY() + 1 - in, p.getZ() + 1 - in,
                    0xFF000000 | b, (0x38 << 24) | b, t, glow.value);
        BlockPos op = shownOpening;
        if (op != null)
            WorldGfx.box(op.getX() + in, op.getY() + in, op.getZ() + in, op.getX() + 1 - in, op.getY() + 1 - in, op.getZ() + 1 - in,
                    0xFF000000 | o, (0x28 << 24) | o, t, glow.value);
        BlockPos web = shownWeb;
        if (web != null)
            WorldGfx.box(web.getX() + in, web.getY() + in, web.getZ() + in, web.getX() + 1 - in, web.getY() + 1 - in, web.getZ() + 1 - in,
                    0xFF000000 | w, (0x40 << 24) | w, t, glow.value);
    }
}
