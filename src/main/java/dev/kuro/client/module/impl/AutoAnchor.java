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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Place / charge / detonate respawn anchors near targets.
 * Kuro-native implementation of the flow Respect Anchor/AnchorAura uses (place → glowstone → click explode).
 */
public class AutoAnchor extends Module {
    private final Setting.Num range = add(new Setting.Num("Range", 4.5, 2.0, 6.0, 0.1));
    private final Setting.Num minDamage = add(new Setting.Num("Min damage", 8.0, 0.0, 36.0, 0.5));
    private final Setting.Num maxSelf = add(new Setting.Num("Max self", 10.0, 0.0, 36.0, 0.5));
    private final Setting.Num delay = add(new Setting.Num("Delay", 1, 0, 10, 1));
    private final Setting.Bool silent = add(new Setting.Bool("Silent rotate", true));
    private final Setting.Bool autoSwitch = add(new Setting.Bool("Auto switch", true));

    private enum Phase { PLACE, CHARGE, DETONATE }
    private Phase phase = Phase.PLACE;
    private BlockPos anchorPos;
    private int cd;

    public AutoAnchor() { super("Auto Anchor", Category.COMBAT); }

    @Override
    public void onDisable() {
        Rotations.release(this);
        phase = Phase.PLACE;
        anchorPos = null;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.screen != null) return;
        if (mc.player.isSpectator()) return;
        if (cd > 0) { cd--; return; }

        LivingEntity target = bestTarget();
        if (target == null) { reset(); return; }

        // Prefer an existing charged/uncharged anchor in range
        BlockPos existing = findAnchorNear(target);
        if (existing != null) {
            anchorPos = existing;
            BlockState st = mc.level.getBlockState(existing);
            int charges = st.hasProperty(RespawnAnchorBlock.CHARGE) ? st.getValue(RespawnAnchorBlock.CHARGE) : 0;
            if (charges > 0) phase = Phase.DETONATE;
            else phase = Phase.CHARGE;
        }

        switch (phase) {
            case PLACE -> doPlace(target);
            case CHARGE -> doCharge();
            case DETONATE -> doDetonate(target);
        }
    }

    private void doPlace(LivingEntity target) {
        BlockPos spot = findPlace(target);
        if (spot == null) return;
        int slot = BlockPlacer.findSlot(s -> s.is(Items.RESPAWN_ANCHOR));
        if (slot < 0) return;
        if (autoSwitch.value && !BlockPlacer.select(slot)) return;
        aim(Vec3.atCenterOf(spot));
        if (!BlockPlacer.place(spot)) return;
        anchorPos = spot;
        phase = Phase.CHARGE;
        cd = delay.intValue();
    }

    private void doCharge() {
        if (anchorPos == null || !mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
            reset(); return;
        }
        int glow = BlockPlacer.findSlot(s -> s.is(Items.GLOWSTONE));
        if (glow < 0) return;
        if (autoSwitch.value && !BlockPlacer.select(glow)) return;
        aim(Vec3.atCenterOf(anchorPos));
        click(anchorPos);
        phase = Phase.DETONATE;
        cd = delay.intValue();
    }

    private void doDetonate(LivingEntity target) {
        if (anchorPos == null || !mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
            reset(); return;
        }
        // Don't hold glowstone when detonating
        int nonGlow = BlockPlacer.findSlot(s -> !s.is(Items.GLOWSTONE) && !s.isEmpty());
        if (nonGlow >= 0 && mc.player.getMainHandItem().is(Items.GLOWSTONE)) {
            BlockPlacer.select(nonGlow);
            return;
        }
        double dmg = anchorDamage(Vec3.atCenterOf(anchorPos), target);
        double self = anchorDamage(Vec3.atCenterOf(anchorPos), mc.player);
        if (dmg < minDamage.value || self > maxSelf.value) { reset(); return; }
        aim(Vec3.atCenterOf(anchorPos));
        click(anchorPos);
        reset();
        cd = delay.intValue();
    }

    private void click(BlockPos pos) {
        Vec3 hit = Vec3.atCenterOf(pos);
        BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, bhr);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void aim(Vec3 point) {
        if (!silent.value) return;
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f;
        float pitch = (float) (-(Mth.atan2(dy, dist) * (180 / Math.PI)));
        Rotations.set(this, yaw, pitch);
        Rotations.sendLookPacket();
    }

    private void reset() {
        phase = Phase.PLACE;
        anchorPos = null;
        Rotations.release(this);
    }

    private LivingEntity bestTarget() {
        LivingEntity best = null;
        double bestD = range.value + 6;
        for (Player p : mc.level.getEntitiesOfClass(Player.class, mc.player.getBoundingBox().inflate(bestD), Entity::isAlive)) {
            if (p == mc.player || p.isSpectator()) continue;
            if (Friends.shield(p) || AntiBot.isBot(p)) continue;
            double d = mc.player.distanceTo(p);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private BlockPos findPlace(LivingEntity target) {
        BlockPos center = target.blockPosition();
        BlockPos best = null;
        double bestScore = -1;
        int r = (int) Math.ceil(range.value);
        for (int x = -r; x <= r; x++) for (int y = -1; y <= 2; y++) for (int z = -r; z <= r; z++) {
            BlockPos p = center.offset(x, y, z);
            if (!mc.level.getBlockState(p).canBeReplaced()) continue;
            if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > range.value) continue;
            // need a solid face to click
            boolean support = false;
            for (Direction d : Direction.values()) {
                if (!mc.level.getBlockState(p.relative(d)).canBeReplaced()) { support = true; break; }
            }
            if (!support) continue;
            double dmg = anchorDamage(Vec3.atCenterOf(p), target);
            double self = anchorDamage(Vec3.atCenterOf(p), mc.player);
            if (dmg < minDamage.value || self > maxSelf.value) continue;
            double score = dmg - self * 0.5;
            if (score > bestScore) { bestScore = score; best = p; }
        }
        return best;
    }

    private BlockPos findAnchorNear(LivingEntity target) {
        BlockPos center = target.blockPosition();
        int r = (int) Math.ceil(range.value);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = -r; x <= r; x++) for (int y = -2; y <= 3; y++) for (int z = -r; z <= r; z++) {
            BlockPos p = center.offset(x, y, z);
            if (!mc.level.getBlockState(p).is(Blocks.RESPAWN_ANCHOR)) continue;
            double d = mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p));
            if (d <= range.value && d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    public static double anchorDamage(Vec3 pos, LivingEntity e) {
        double dist = e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(pos) / 10.0;
        if (dist > 1.0) return 0;
        double exposure = 1.0 - dist;
        double impact = exposure * exposure;
        return (impact * impact + impact) * 6.0 * 10.0 / 2.0 + 1.0;
    }
}
