package dev.kuro.client.echo.anchor;

import dev.kuro.client.echo.Imports;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks an aim point on a respawn anchor that the player can actually click.
 *
 * <p>The naive approach -- always aim at the eye-side corner of the eye-facing
 * face -- fails when an adjacent block (the safety block we just placed,
 * obsidian, etc.) occludes that corner: the raycast lands on the neighbor and
 * the interaction is rejected. So we walk every visible face in
 * most-facing-first order, and for each face try a series of sample points
 * (random near-corner -> inward toward face center -> opposite quadrants),
 * returning the first one whose ray from the eye actually hits the anchor.
 */
public final class AnchorAimSearch implements Imports {

    private AnchorAimSearch() {}

    /** Find an aim point on {@code anchorPos} that raycasts cleanly from the player's eye. */
    public static Vec3 findClearAimPoint(BlockPos anchorPos, float partialTick) {
        if (anchorPos == null) return null;
        if (mc.player == null || mc.level == null) return Vec3.atCenterOf(anchorPos);

        Vec3 eye = mc.player.getEyePosition(partialTick);
        Vec3 anchorCenter = Vec3.atCenterOf(anchorPos);
        Vec3 toEye = eye.subtract(anchorCenter);

        Direction[] sorted = Direction.values().clone();
        Arrays.sort(sorted, (a, b) -> Double.compare(dotEye(b, toEye), dotEye(a, toEye)));

        for (Direction faceDir : sorted) {
            if (dotEye(faceDir, toEye) <= 0.0) continue; // back face -- can't hit it from here
            Vec3 candidate = searchFace(eye, anchorPos, anchorCenter, faceDir);
            if (candidate != null) return candidate;
        }
        return anchorCenter;
    }

    /** True if {@code aimPoint} raycasts from {@code eye} and lands on {@code anchorPos} (not an adjacent block). */
    public static boolean canHitAnchor(Vec3 eye, Vec3 aimPoint, BlockPos anchorPos) {
        if (mc.level == null || mc.player == null) return false;
        Vec3 dir = aimPoint.subtract(eye);
        if (dir.lengthSqr() < 1.0E-8) return true;
        Vec3 end = eye.add(dir.normalize().scale(mc.player.blockInteractionRange()));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eye, end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                mc.player
        ));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(anchorPos);
    }

    private static double dotEye(Direction dir, Vec3 toEye) {
        return dir.getStepX() * toEye.x + dir.getStepY() * toEye.y + dir.getStepZ() * toEye.z;
    }

    private static Vec3 searchFace(Vec3 eye, BlockPos anchorPos, Vec3 anchorCenter, Direction faceDir) {
        double fcx = anchorCenter.x + faceDir.getStepX() * 0.5;
        double fcy = anchorCenter.y + faceDir.getStepY() * 0.5;
        double fcz = anchorCenter.z + faceDir.getStepZ() * 0.5;
        Direction.Axis axis = faceDir.getAxis();

        double signA, signB;
        switch (axis) {
            case X -> {
                signA = eye.y >= anchorCenter.y ? 1.0 : -1.0;
                signB = eye.z >= anchorCenter.z ? 1.0 : -1.0;
            }
            case Y -> {
                signA = eye.x >= anchorCenter.x ? 1.0 : -1.0;
                signB = eye.z >= anchorCenter.z ? 1.0 : -1.0;
            }
            default -> {
                signA = eye.x >= anchorCenter.x ? 1.0 : -1.0;
                signB = eye.y >= anchorCenter.y ? 1.0 : -1.0;
            }
        }

        // Random near-corner so consecutive sequences don't pick identical yaws
        // (helps avoid Grim DuplicateRotPlace lining up across cycles).
        double startOff = 0.30 + ThreadLocalRandom.current().nextDouble() * 0.10; // [0.30, 0.40]

        double[][] sampleOffsets = {
                { signA * startOff, signB * startOff },
                { signA * 0.25,     signB * 0.25 },
                { signA * 0.20,     signB * 0.20 },
                { signA * 0.15,     signB * 0.15 },
                { signA * 0.10,     signB * 0.10 },
                { signA * 0.30,     0.0 },
                { 0.0,              signB * 0.30 },
                { 0.0,              0.0 },
                { -signA * 0.30,    signB * 0.30 },
                { signA * 0.30,    -signB * 0.30 },
                { -signA * 0.30,   -signB * 0.30 },
                { -signA * 0.30,    0.0 },
                { 0.0,             -signB * 0.30 },
        };

        for (double[] off : sampleOffsets) {
            Vec3 candidate = faceSamplePoint(axis, fcx, fcy, fcz, off[0], off[1]);
            if (canHitAnchor(eye, candidate, anchorPos)) return candidate;
        }
        return null;
    }

    private static Vec3 faceSamplePoint(Direction.Axis axis, double fcx, double fcy, double fcz,
                                        double offA, double offB) {
        return switch (axis) {
            case X -> new Vec3(fcx, fcy + offA, fcz + offB);
            case Y -> new Vec3(fcx + offA, fcy, fcz + offB);
            case Z -> new Vec3(fcx + offA, fcy + offB, fcz);
        };
    }
}
