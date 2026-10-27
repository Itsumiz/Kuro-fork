package dev.kuro.client.echo.utils.blocks;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import static dev.kuro.client.echo.Imports.mc;

public class BlockUtils {
    private static final double PLACE_RAY_EPSILON = 1.0E-4;
    private static final double PLACE_FACE_CORNER_OFFSET = 0.49;
    private static final double[][] PLACE_FACE_SAMPLE_OFFSETS = {
        {0.0, 0.0},
        {PLACE_FACE_CORNER_OFFSET, PLACE_FACE_CORNER_OFFSET},
        {PLACE_FACE_CORNER_OFFSET, -PLACE_FACE_CORNER_OFFSET},
        {-PLACE_FACE_CORNER_OFFSET, PLACE_FACE_CORNER_OFFSET},
        {-PLACE_FACE_CORNER_OFFSET, -PLACE_FACE_CORNER_OFFSET}
    };

    public static boolean isBlock(BlockPos pos, Block block) {
        return mc.level.getBlockState(pos).getBlock() == block;
    }

    public static boolean isBlockAtPosition(BlockPos blockPos, Block block) {
        return mc.level.getBlockState(blockPos).getBlock() == block;
    }

    public static boolean isRespawnAnchorCharged(BlockPos blockPos) {
        return isBlockAtPosition(blockPos, Blocks.RESPAWN_ANCHOR) && mc.level.getBlockState(blockPos).getValue(RespawnAnchorBlock.CHARGE) != 0;
    }

    public static boolean isRespawnAnchorUncharged(BlockPos blockPos) {
        return isBlockAtPosition(blockPos, Blocks.RESPAWN_ANCHOR) && mc.level.getBlockState(blockPos).getValue(RespawnAnchorBlock.CHARGE) == 0;
    }

    public static InteractionResult interactWithBlock(BlockHitResult blockHitResult, boolean shouldSwingHand) {
        if (mc.player == null || mc.gameMode == null) return InteractionResult.PASS;

        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHitResult);
        if (result.consumesAction() && shouldSwingHand && result instanceof InteractionResult.Success success) {
            InteractionResult.SwingSource swingSource = success.swingSource();
            if (swingSource == InteractionResult.SwingSource.CLIENT || swingSource == InteractionResult.SwingSource.SERVER) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        return result;
    }

    public static boolean willPlacedBlockPlacementCollideWithEntity(BlockPos blockPos, BlockState blockState) {
        if (mc.level == null) return false;
        if (blockPos == null) return false;
        if (blockState == null) return false;

        VoxelShape collisionShape = blockState.getCollisionShape(mc.level, blockPos);

        AABB searchBox = new AABB(blockPos);
        var entities = mc.level.getEntitiesOfClass(
            LivingEntity.class,
            searchBox,
            e -> e != mc.player
        );

        for (LivingEntity entity : entities) {
            if (entity.getBoundingBox().intersects(searchBox)) {
                return true;
            }
        }
        return false;
    }


    public static boolean isBlockPlacingCollideWithEntity(BlockPos blockPos, Block block) {
        return willPlacedBlockPlacementCollideWithEntity(blockPos, block.defaultBlockState());
    }

    /** True if the player's hitbox occupies the block AABB at {@code pos}. */
    public static boolean collidesWithPlayer(BlockPos pos) {
        return mc.player != null && pos != null && mc.player.getBoundingBox().intersects(new AABB(pos));
    }

    /** True if an entity that blocks building overlaps the block AABB at {@code pos}. */
    public static boolean collidesWithBlockingEntity(BlockPos pos) {
        if (pos == null || mc.level == null) return false;
        return hasBlockingEntity(new AABB(pos));
    }

    public static boolean hasBlockingEntity(AABB box) {
        if (box == null || mc.level == null) return false;
        for (Entity entity : mc.level.getEntities(null, box)) {
            if (!isBlockingEntity(entity)) continue;
            if (entity.getBoundingBox().intersects(box)) return true;
        }
        return false;
    }

    private static boolean isBlockingEntity(Entity entity) {
        if (entity == null || entity == mc.player) return false;
        return !entity.isRemoved() && entity.blocksBuilding;
    }

    public static BlockPos getTargetPlacePos() {
        HitResult hitResult = mc.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        
        BlockHitResult blockHitResult = (BlockHitResult) hitResult;
        return blockHitResult.getBlockPos().relative(blockHitResult.getDirection());
    }

    public static boolean isValidPlacement(BlockPos pos) {
        if (pos == null || mc.level == null) return false;
        
        BlockState state = mc.level.getBlockState(pos);
        if (!state.isAir() && !state.canBeReplaced()) return false;

        return true;
    }

    public static boolean hasSupportingFace(BlockPos pos) {
        if (pos == null || mc.level == null) return false;

        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.relative(direction);
            if (mc.level.getBlockState(adjacent).isFaceSturdy(mc.level, adjacent, direction.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasCrystalOnBlock(BlockPos pos) {
        if (mc.level == null) return false;

        BlockPos crystalPos = pos.above();
        if (!mc.level.isEmptyBlock(crystalPos)) return false;

        double x = crystalPos.getX();
        double y = crystalPos.getY();
        double z = crystalPos.getZ();

        AABB crystalAABB = new AABB(x, y, z, x + 1.0, y + 2.0, z + 1.0);
        return !mc.level.getEntities(null, crystalAABB).isEmpty();
    }

    public static boolean canPlaceLegit(BlockPos pos) {
        return findPlacementHit(pos, true) != null;
    }

    public static PlacementHit findPlacementHit(BlockPos pos, boolean requireRaycast) {
        if (pos == null || mc.level == null || mc.player == null) return null;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 eyePos = mc.player.getEyePosition(pt);
        PlacementHit bestHit = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.relative(direction);
            Direction face = direction.getOpposite();

            if (!mc.level.getBlockState(adjacent).isFaceSturdy(mc.level, adjacent, face)) {
                continue;
            }

            PlacementHit hit = findPlacementHitOnFace(eyePos, pos, adjacent, direction, face, requireRaycast);
            if (hit == null) continue;

            double distanceSq = eyePos.distanceToSqr(hit.hitResult().getLocation());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestHit = hit;
            }
        }

        return bestHit;
    }

    private static PlacementHit findPlacementHitOnFace(Vec3 eyePos, BlockPos placementPos, BlockPos adjacent, Direction direction, Direction face, boolean requireRaycast) {
        for (double[] sampleOffset : PLACE_FACE_SAMPLE_OFFSETS) {
            Vec3 facePoint = getFaceSamplePoint(adjacent, face, sampleOffset[0], sampleOffset[1]);
            BlockHitResult hitResult = new BlockHitResult(facePoint, face, adjacent, false);

            if (requireRaycast && !canRaycastPlacementFace(eyePos, hitResult, direction)) {
                continue;
            }

            return new PlacementHit(placementPos, hitResult);
        }

        return null;
    }

    private static boolean canRaycastPlacementFace(Vec3 eyePos, BlockHitResult placementHit, Direction direction) {
        Vec3 facePoint = placementHit.getLocation();
        Vec3 rayEnd = facePoint.add(
            direction.getStepX() * PLACE_RAY_EPSILON,
            direction.getStepY() * PLACE_RAY_EPSILON,
            direction.getStepZ() * PLACE_RAY_EPSILON
        );

        BlockHitResult hitResult = mc.level.clip(new ClipContext(
            eyePos,
            rayEnd,
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            mc.player
        ));

        if (hitResult.getType() != HitResult.Type.BLOCK) return false;
        if (!hitResult.getBlockPos().equals(placementHit.getBlockPos())) return false;
        return hitResult.getDirection() == placementHit.getDirection();
    }

    private static Vec3 getFaceSamplePoint(BlockPos adjacent, Direction face, double offsetA, double offsetB) {
        Vec3 faceCenter = Vec3.atCenterOf(adjacent).add(
            face.getStepX() * 0.5,
            face.getStepY() * 0.5,
            face.getStepZ() * 0.5
        );

        return switch (face.getAxis()) {
            case X -> faceCenter.add(0.0, offsetA, offsetB);
            case Y -> faceCenter.add(offsetA, 0.0, offsetB);
            case Z -> faceCenter.add(offsetA, offsetB, 0.0);
        };
    }

    public record PlacementHit(BlockPos placementPos, BlockHitResult hitResult) {}

}
