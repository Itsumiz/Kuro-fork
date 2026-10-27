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
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crystal place + break aura for Kuro.
 * Behavior is modelled on Respect CrystalAura (target, place on obsidian/bedrock, break crystals)
 * but implemented against Kuro Module/Setting/AttackPackets/Rotations — not a paste of Respect sources.
 */
public class CrystalAura extends Module {
    private final Setting.Num placeRange = add(new Setting.Num("Place range", 4.5, 2.0, 6.0, 0.1));
    private final Setting.Num breakRange = add(new Setting.Num("Break range", 4.5, 2.0, 6.0, 0.1));
    private final Setting.Num wallRange = add(new Setting.Num("Wall range", 3.0, 0.0, 6.0, 0.1));
    private final Setting.Num minDamage = add(new Setting.Num("Min damage", 6.0, 0.0, 36.0, 0.5));
    private final Setting.Num maxSelf = add(new Setting.Num("Max self damage", 8.0, 0.0, 36.0, 0.5));
    private final Setting.Num placeDelay = add(new Setting.Num("Place delay", 0, 0, 10, 1));
    private final Setting.Num breakDelay = add(new Setting.Num("Break delay", 0, 0, 10, 1));
    private final Setting.Bool silent = add(new Setting.Bool("Silent rotate", true));
    private final Setting.Bool autoSwitch = add(new Setting.Bool("Auto switch", true));
    private final Setting.Bool players = add(new Setting.Bool("Players", true));
    private final Setting.Bool predict = add(new Setting.Bool("Predict motion", true));

    private int placeCd, breakCd;
    private BlockPos pendingPlace;

    public CrystalAura() { super("Crystal Aura", Category.COMBAT); }

    @Override
    public void onDisable() { Rotations.release(this); pendingPlace = null; }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.screen != null) return;
        if (mc.player.isSpectator()) return;
        if (placeCd > 0) placeCd--;
        if (breakCd > 0) breakCd--;

        // 1) Break existing crystals that hurt the target more than us
        if (breakCd <= 0) {
            EndCrystal best = null;
            double bestScore = -1;
            double br = breakRange.value;
            AABB area = mc.player.getBoundingBox().inflate(br + 1.5);
            LivingEntity target = bestTarget();
            for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, area, Entity::isAlive)) {
                if (mc.player.distanceTo(c) > br) continue;
                double dmg = target == null ? 0 : crystalDamage(c.position(), target);
                double self = crystalDamage(c.position(), mc.player);
                if (self > maxSelf.value) continue;
                if (target != null && dmg < minDamage.value) continue;
                double score = dmg - self * 0.5;
                if (score > bestScore) { bestScore = score; best = c; }
            }
            if (best != null) {
                aimAt(best.position().add(0, 0.5, 0));
                AttackPackets.hit(best);
                breakCd = breakDelay.intValue();
                return;
            }
        }

        // 2) Place if we have a good spot near a target
        if (placeCd > 0) return;
        LivingEntity target = bestTarget();
        if (target == null) { Rotations.release(this); return; }

        BlockPos spot = findPlace(target);
        if (spot == null) { Rotations.release(this); return; }

        int crystalSlot = BlockPlacer.findSlot(s -> s.is(Items.END_CRYSTAL));
        if (crystalSlot < 0 && !mc.player.getOffhandItem().is(Items.END_CRYSTAL)) return;
        if (autoSwitch.value && crystalSlot >= 0 && !mc.player.getMainHandItem().is(Items.END_CRYSTAL)
                && !mc.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            if (!BlockPlacer.select(crystalSlot)) return;
        }

        Vec3 hit = Vec3.atCenterOf(spot).add(0, 0.5, 0);
        aimAt(hit);
        InteractionHand hand = mc.player.getOffhandItem().is(Items.END_CRYSTAL)
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, spot, false);
        mc.gameMode.useItemOn(mc.player, hand, bhr);
        mc.player.swing(hand);
        placeCd = placeDelay.intValue();
        pendingPlace = spot;
    }

    private void aimAt(Vec3 point) {
        if (!silent.value) return;
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f;
        float pitch = (float) (-(Mth.atan2(dy, dist) * (180 / Math.PI)));
        Rotations.set(this, yaw, pitch);
        Rotations.sendLookPacket();
    }

    private LivingEntity bestTarget() {
        LivingEntity best = null;
        double bestD = placeRange.value + 8;
        AABB area = mc.player.getBoundingBox().inflate(bestD);
        for (Player p : mc.level.getEntitiesOfClass(Player.class, area, Entity::isAlive)) {
            if (p == mc.player || p.isSpectator()) continue;
            if (!players.value) continue;
            if (Friends.shield(p) || AntiBot.isBot(p)) continue;
            double d = mc.player.distanceTo(p);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private BlockPos findPlace(LivingEntity target) {
        Vec3 tpos = predict.value
                ? target.position().add(target.getDeltaMovement().scale(1.5))
                : target.position();
        BlockPos center = BlockPos.containing(tpos);
        BlockPos best = null;
        double bestScore = -1;
        int r = (int) Math.ceil(placeRange.value);
        for (int x = -r; x <= r; x++) for (int y = -2; y <= 3; y++) for (int z = -r; z <= r; z++) {
            BlockPos base = center.offset(x, y, z);
            if (!canPlaceOn(base)) continue;
            BlockPos air = base.above();
            if (!mc.level.getBlockState(air).canBeReplaced()) continue;
            if (!mc.level.getEntitiesOfClass(Entity.class, new AABB(air).inflate(0.1)).isEmpty()) continue;
            Vec3 crystal = Vec3.atBottomCenterOf(air).add(0, 0.5, 0);
            if (mc.player.getEyePosition().distanceTo(crystal) > placeRange.value) continue;
            double dmg = crystalDamage(crystal, target);
            double self = crystalDamage(crystal, mc.player);
            if (dmg < minDamage.value || self > maxSelf.value) continue;
            double score = dmg - self * 0.6 - mc.player.distanceToSqr(crystal) * 0.02;
            if (score > bestScore) { bestScore = score; best = base; }
        }
        return best;
    }

    private boolean canPlaceOn(BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK);
    }

    /** Rough crystal damage estimate (not full ExplosionDamageCalculator). */
    private static double crystalDamage(Vec3 crystal, LivingEntity e) {
        double dist = e.position().add(0, e.getBbHeight() * 0.5, 0).distanceTo(crystal) / 12.0;
        if (dist > 1.0) return 0;
        double exposure = 1.0 - dist;
        double impact = exposure * exposure;
        return (impact * impact + impact) * 7.0 * 12.0 / 2.0 + 1.0;
    }
}
