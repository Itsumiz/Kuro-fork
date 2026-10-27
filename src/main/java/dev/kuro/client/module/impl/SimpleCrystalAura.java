package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Simple Crystal Aura: port of the small "Crystal Aura" from the ru.white client. Kuro already has a damage-based
 * "Crystal Aura", so this one is registered separately.
 *
 *  - Breaks the nearest end crystal in range (timer in ms).
 *  - Places a crystal on the nearest obsidian / bedrock with two free blocks above it (optionally only near an enemy).
 *  - Swaps to the crystal hotbar slot for the place and swaps back afterwards.
 *
 * No damage calculation: it does not weigh self damage, so stay out of range of your own crystals.
 */
public class SimpleCrystalAura extends Module {
    private final Setting.Num range = add(new Setting.Num("Range", 5.0, 1.0, 6.0, 0.5));
    private final Setting.Num placeDelay = add(new Setting.Num("Place delay (ms)", 200, 50, 1000, 50));
    private final Setting.Num breakDelay = add(new Setting.Num("Break delay (ms)", 100, 50, 1000, 50));
    private final Setting.Bool rotate = add(new Setting.Bool("Rotate to block", true));
    private final Setting.Bool onlyNearEnemy = add(new Setting.Bool("Only near enemy", true));
    private final Setting.Num enemyRange = add(new Setting.Num("Enemy range", 6.0, 1.0, 15.0, 0.5));
    private final Setting.Bool obsidianOnly = add(new Setting.Bool("Obsidian only", false));

    private long lastPlace, lastBreak;
    private int prevSlot = -1;

    public SimpleCrystalAura() { super("Simple Crystal Aura", Category.COMBAT); }

    @Override
    protected void onDisable() {
        if (prevSlot != -1 && mc.player != null) BlockPlacer.selectForce(prevSlot);
        prevSlot = -1;
        Rotations.release(this);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.screen != null) return;
        if (mc.player.isSpectator()) return;

        if (breakNearest()) return;                         // one action per tick keeps packet order tidy

        BlockPos spot = findSpot();
        if (spot == null) { restoreSlot(); Rotations.release(this); return; }

        boolean offhand = mc.player.getOffhandItem().is(Items.END_CRYSTAL);
        int slot = BlockPlacer.findSlot(s -> s.is(Items.END_CRYSTAL));
        if (!offhand && slot < 0) { restoreSlot(); Rotations.release(this); return; }

        Vec3 hit = new Vec3(spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5);
        if (rotate.value) aimAt(hit);

        long now = System.currentTimeMillis();
        if (now - lastPlace < (long) placeDelay.value) return;

        if (!offhand && BlockPlacer.selected() != slot) {
            if (prevSlot == -1) prevSlot = BlockPlacer.selected();
            if (!BlockPlacer.select(slot)) return;          // a swap was already made this tick: place next tick
        }
        InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(hit, Direction.UP, spot, false));
        mc.player.swing(hand);
        lastPlace = now;
    }

    /** Hits the closest crystal in range when the break timer allows. Returns true when a hit was queued. */
    private boolean breakNearest() {
        long now = System.currentTimeMillis();
        if (now - lastBreak < (long) breakDelay.value) return false;

        EndCrystal nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EndCrystal c) || !c.isAlive()) continue;
            double d = mc.player.distanceTo(c);
            if (d <= range.value && d < best) { best = d; nearest = c; }
        }
        if (nearest == null) return false;

        if (rotate.value) aimAt(nearest.position().add(0, 0.5, 0));
        AttackPackets.hit(nearest);
        lastBreak = now;
        return true;
    }

    private BlockPos findSpot() {
        int r = (int) Math.floor(range.value);
        BlockPos origin = mc.player.blockPosition();
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;

        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            BlockPos pos = origin.offset(x, y, z);
            BlockState s = mc.level.getBlockState(pos);
            boolean base = s.is(Blocks.OBSIDIAN) || (!obsidianOnly.value && s.is(Blocks.BEDROCK));
            if (!base) continue;
            if (!mc.level.getBlockState(pos.above()).isAir() || !mc.level.getBlockState(pos.above(2)).isAir()) continue;
            double d = eye.distanceTo(Vec3.atCenterOf(pos));
            if (d > range.value || d >= bestD) continue;
            if (onlyNearEnemy.value && !enemyNear(pos)) continue;
            if (!mc.level.getEntitiesOfClass(Entity.class,
                    new AABB(pos.getX(), pos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 3, pos.getZ() + 1)).isEmpty()) continue;
            bestD = d;
            best = pos;
        }
        return best;
    }

    private boolean enemyNear(BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator() || !p.isAlive()) continue;
            if (Friends.shield(p) || AntiBot.isBot(p)) continue;
            if (p.position().distanceTo(c) <= enemyRange.value) return true;
        }
        return false;
    }

    private void restoreSlot() {
        if (prevSlot == -1) return;
        if (BlockPlacer.select(prevSlot)) prevSlot = -1;
    }

    /** Silent aim: the server gets the angles in a look packet right before the click, your camera stays put. */
    private void aimAt(Vec3 point) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(dy, Math.max(h, 1e-4))), -90f, 90f);
        if (Rotations.set(this, yaw, pitch)) Rotations.sendLookPacket();
    }
}
