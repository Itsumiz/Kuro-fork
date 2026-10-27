package dev.kuro.client.util;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import dev.kuro.client.mixin.InteractionManagerInvoker;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.function.Predicate;

/** Shared helpers for modules that place blocks / pick hotbar slots. */
public final class BlockPlacer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final Direction[] ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP };
    public static final double REACH = 4.5;

    private BlockPlacer() {}

    /** Places the held block at target by clicking an adjacent solid block. Returns false if impossible. */
    public static boolean place(BlockPos target) {
        if (mc.level == null || mc.player == null || mc.gameMode == null) return false;
        if (!mc.level.getBlockState(target).canBeReplaced()) return false;
        if (mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) > REACH * REACH) return false;
        for (Direction d : ORDER) {
            BlockPos n = target.relative(d);
            if (mc.level.getBlockState(n).canBeReplaced()) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, n, false));
            mc.player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ vanilla-style placement

    public enum PlaceResult {
        /** The block was placed (the server accepted the use). */
        DONE,
        /** A rule says "not this tick" (swap already made, rotation owned by another module, ...): try again next tick. */
        LATER,
        /** This spot cannot be placed from where you are (no valid face, out of reach, something in the way, refused). */
        SKIP,
        /** (placeFromView only) not looking at a valid click yet: turn the camera, then try next tick. */
        AIM
    }

    /**
     * Places the block in hotbar slot 'slot' at 'target' the way a real client does, and refuses anything a real client could not do.
     *
     * Call it at the START of a client tick (Module.onPreTick), where a vanilla client handles a right-click: the use packet then
     * goes out before this tick's movement packet. Packet order: 1. hotbar slot packet (only if the slot changed), 2. look packet
     * with the aim angles, 3. use-item-on packet, 4. swing packet (only when the result asks the client for it); then the real
     * view angles are put back and the next movement packet carries them again.
     *
     * State checks (nothing is sent when one fails): can build (not spectator / adventure), not using another item, the target
     * is free and the block could stand there and no entity is in the spot, inside the world border and build height. The click
     * itself is real: an aim point is searched on a face of an existing block that borders the target, the angles are snapped
     * to the mouse step of your sensitivity, and the ray from your eyes along those angles is cast the way the game casts it
     * (outline shapes, block reach 4.5). It has to hit exactly that block and face, and no entity you could click may stand
     * between you and the hit point (the game would interact with the entity instead). The BlockHitResult sent is that ray's
     * own result, not a made-up point. The aim closest to where you already look is used.
     */
    public static PlaceResult placeAt(Object owner, int slot, BlockPos target) {
        PlaceResult pre = check(slot, target);
        if (pre != null) return pre;
        Player p = mc.player;
        Aim aim = findAim(p, target);
        if (aim == null) return PlaceResult.SKIP;
        if (!Rotations.free() && !Rotations.ownedBy(owner)) return PlaceResult.LATER;     // another module is using the silent angles
        if (!select(slot)) return PlaceResult.LATER;                                      // one swap per tick: not now

        // 1. slot packet now, so it is on the wire before the look and use packets
        ((InteractionManagerInvoker) mc.gameMode).kuro$syncSelectedSlot();
        // 2. look packet with the aim angles (the use packet below is evaluated by the server with the same angles)
        if (!Rotations.set(owner, aim.yaw, aim.pitch) || !Rotations.sendLookPacket(true)) {
            Rotations.release(owner);
            return PlaceResult.LATER;
        }
        float oldYaw = p.getYRot(), oldPitch = p.getXRot();
        p.setYRot(aim.yaw);
        p.setXRot(aim.pitch);
        // 3. use-item-on packet
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, aim.hit);
        // 4. swing packet, only when the result asks the client for it (what Minecraft.startUseItem does)
        if (result instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT) {
            p.swing(InteractionHand.MAIN_HAND);
        }
        // the screen never shows the aim angles; the next movement packet carries the real ones (Rotations.consumeRestore)
        p.setYRot(oldYaw);
        p.setXRot(oldPitch);
        Rotations.release(owner);
        return result.consumesAction() ? PlaceResult.DONE : PlaceResult.SKIP;
    }

    /** The state checks every placement needs. Null = all fine, otherwise the result to return (nothing was sent). */
    private static PlaceResult check(int slot, BlockPos target) {
        if (mc.level == null || mc.player == null || mc.gameMode == null) return PlaceResult.LATER;
        Player p = mc.player;
        if (mc.screen != null || p.isSpectator() || !p.isAlive() || p.isUsingItem()) return PlaceResult.LATER;
        if (!p.mayBuild()) return PlaceResult.SKIP;
        ItemStack stack = p.getInventory().getItem(slot);
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return PlaceResult.SKIP;
        if (!mc.level.getBlockState(target).canBeReplaced()) return PlaceResult.SKIP;
        if (mc.level.isOutsideBuildHeight(target) || !mc.level.getWorldBorder().isWithinBounds(target)) return PlaceResult.SKIP;
        BlockState placed = bi.getBlock().defaultBlockState();
        if (!placed.canSurvive(mc.level, target) || !mc.level.isUnobstructed(placed, target, CollisionContext.empty())) return PlaceResult.SKIP;
        return null;
    }

    /** Result of placeFromView: AIM means "not looking at a valid click yet, turn to yaw / pitch first" (nothing was sent). */
    public record Attempt(PlaceResult result, float yaw, float pitch) {}

    /**
     * Camera version of placeAt: no look packet. The click is made with the angles the server already has (the last movement
     * packet carried them), so call it at the start of a tick and turn the real camera yourself (turnToward) when it answers AIM;
     * the click can then go out on the next tick. Packet order is the plain vanilla one: slot packet, use-item-on, swing.
     * The click must hit exactly a face next to the target (same ray rules as placeAt) with no entity in the way.
     */
    public static Attempt placeFromView(Object owner, int slot, BlockPos target) {
        PlaceResult pre = check(slot, target);
        if (pre != null) return new Attempt(pre, 0f, 0f);
        Player p = mc.player;
        if (!Rotations.free()) return new Attempt(PlaceResult.LATER, 0f, 0f);          // another module owns the silent angles
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(p.getXRot(), p.getYRot()).scale(REACH));
        BlockHitResult hr = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        boolean onTarget = hr.getType() == HitResult.Type.BLOCK
                && hr.getBlockPos().relative(hr.getDirection()).equals(target)
                && !mc.level.getBlockState(hr.getBlockPos()).canBeReplaced()
                && !entityInTheWay(p, eye, hr.getLocation());
        if (!onTarget) {
            Aim aim = findAim(p, target);
            return aim == null ? new Attempt(PlaceResult.SKIP, 0f, 0f) : new Attempt(PlaceResult.AIM, aim.yaw, aim.pitch);
        }
        if (!select(slot)) return new Attempt(PlaceResult.LATER, 0f, 0f);              // one swap per tick: not now
        ((InteractionManagerInvoker) mc.gameMode).kuro$syncSelectedSlot();             // 1. slot packet
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hr);   // 2. use-item-on
        if (result instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT) {
            p.swing(InteractionHand.MAIN_HAND);                                        // 3. swing
        }
        return new Attempt(result.consumesAction() ? PlaceResult.DONE : PlaceResult.SKIP, 0f, 0f);
    }

    /**
     * Turns the real camera toward the given angles a bit each call, like a hand on the mouse: a fraction of the remaining error
     * (speed 1..10 = about 19%..95% per tick) with a cap per tick, snapped to the mouse step. Same method as Silent Aura.
     * The next movement packet carries the new angles, which is how the server learns them.
     */
    public static void turnToward(float wantYaw, float wantPitch, double speed) {
        Player p = mc.player;
        float yaw = p.getYRot(), pitch = p.getXRot();
        float yawError = Mth.wrapDegrees(wantYaw - yaw), pitchError = wantPitch - pitch;
        float frac = Mth.clamp(0.1f + (float) speed * 0.085f, 0.1f, 0.95f);
        float capYaw = 8f + (float) speed * 4f, capPitch = 5f + (float) speed * 2.5f;
        float yawStep = Mth.clamp(yawError * frac, -capYaw, capYaw), pitchStep = Mth.clamp(pitchError * frac, -capPitch, capPitch);
        double gcd = mouseStep();
        float newYaw = yaw + yawStep, newPitch = pitch + pitchStep;
        if (gcd > 1.0E-4) {
            newYaw = yaw + (float) (Math.round(yawStep / gcd) * gcd);
            newPitch = pitch + (float) (Math.round(pitchStep / gcd) * gcd);
        }
        newPitch = Mth.clamp(newPitch, -90f, 90f);
        if (newYaw == yaw && newPitch == pitch) return;
        p.setYRot(newYaw);
        p.setXRot(newPitch);
        p.yHeadRot = newYaw;
        p.yRotO += newYaw - yaw;          // keep the camera interpolation smooth (same as a real mouse move)
        p.xRotO += newPitch - pitch;
    }

    /**
     * True when a block at 'target' could really be placed from where you stand right now: the spot is free and there is a visible
     * face of a neighbouring block, inside reach and with no entity in the way, that a real click could hit (the same search
     * placeAt / placeFromView use). Spots behind the target, whose faces are hidden by its body, answer false. Sends nothing.
     */
    public static boolean reachable(BlockPos target) {
        if (mc.level == null || mc.player == null) return false;
        if (!mc.level.getBlockState(target).canBeReplaced()) return false;
        return findAim(mc.player, target) != null;
    }

    private record Aim(float yaw, float pitch, BlockHitResult hit) {}

    /** Searches the faces of the blocks around 'target' for a click that really places a block there. Null = none. */
    private static Aim findAim(Player p, BlockPos target) {
        Vec3 eye = p.getEyePosition();
        float curYaw = p.getYRot(), curPitch = p.getXRot();
        double step = mouseStep();
        double[] offsets = {0.0, -0.3, 0.3};
        Aim best = null;
        double bestCost = Double.MAX_VALUE;
        for (Direction d : ORDER) {
            BlockPos n = target.relative(d);
            if (mc.level.getBlockState(n).canBeReplaced()) continue;               // nothing to click on
            Direction face = d.getOpposite();                                      // the face of n that points at the target
            Direction.Axis a = face.getAxis();
            for (double u : offsets) for (double v : offsets) {
                double px = n.getX() + 0.5 + face.getStepX() * 0.5, py = n.getY() + 0.5 + face.getStepY() * 0.5, pz = n.getZ() + 0.5 + face.getStepZ() * 0.5;
                if (a == Direction.Axis.X) { py += u; pz += v; }
                else if (a == Direction.Axis.Y) { px += u; pz += v; }
                else { px += u; py += v; }
                double dx = px - eye.x, dy = py - eye.y, dz = pz - eye.z;
                double flat = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
                // snap the turn to the mouse step, like real mouse input
                float dYaw = Mth.wrapDegrees(yaw - curYaw), dPitch = pitch - curPitch;
                if (step > 1.0E-4) {
                    dYaw = (float) (Math.round(dYaw / step) * step);
                    dPitch = (float) (Math.round(dPitch / step) * step);
                }
                float qYaw = curYaw + dYaw, qPitch = Mth.clamp(curPitch + dPitch, -90f, 90f);
                // cast the ray the way the game does and require this exact block face
                Vec3 end = eye.add(Vec3.directionFromRotation(qPitch, qYaw).scale(REACH));
                BlockHitResult hr = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
                if (hr.getType() != HitResult.Type.BLOCK || !hr.getBlockPos().equals(n) || hr.getDirection() != face) continue;
                if (entityInTheWay(p, eye, hr.getLocation())) continue;
                double cost = Math.abs(dYaw) + Math.abs(dPitch);
                if (cost < bestCost) { bestCost = cost; best = new Aim(qYaw, qPitch, hr); }
            }
        }
        return best;
    }

    /** True when an entity the game would let you click (pickable, not a spectator) is between the eyes and the hit point. */
    private static boolean entityInTheWay(Player p, Vec3 from, Vec3 to) {
        AABB seg = new AABB(from, to).inflate(1.0);
        for (Entity e : mc.level.getEntities(p, seg, x -> !x.isSpectator() && x.isPickable())) {
            AABB box = e.getBoundingBox().inflate(e.getPickRadius());
            if (box.contains(from) || box.clip(from, to).isPresent()) return true;
        }
        return false;
    }

    /** Degrees one pixel of mouse movement turns the camera at the current sensitivity (vanilla: (s*0.6+0.2)^3 * 8 * 0.15). */
    private static double mouseStep() {
        try {
            double f = mc.options.sensitivity().get() * 0.6 + 0.2;
            return f * f * f * 1.2;
        } catch (Exception e) {
            return 0.0;
        }
    }

    public static int findSlot(Predicate<ItemStack> wanted) {
        for (int i = 0; i < 9; i++) if (wanted.test(mc.player.getInventory().getItem(i))) return i;
        return -1;
    }

    public static int selected() { return mc.player.getInventory().getSelectedSlot(); }
    /** Selects the slot, one swap per tick for all modules (see SwapLimit). False = refused, try again next tick. */
    public static boolean select(int slot) { return SwapLimit.select(slot); }
    /** Clean-up when a module is switched off: selects the slot even if a swap was already made this tick. */
    public static void selectForce(int slot) { SwapLimit.selectNow(slot); }

    /** Plain solid blocks only: no chests/furnaces, sand/gravel, slabs/stairs/plants. */
    public static boolean isSolidBlock(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        return !(b instanceof BaseEntityBlock) && !(b instanceof FallingBlock)
                && b.defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO);
    }

    /** Nearest other living, non-spectator player within range, or null. */
    public static Player nearestPlayer(double range) {
        Player best = null;
        double bestD = range;
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator() || !p.isAlive()) continue;
            double d = mc.player.distanceTo(p);
            if (d <= bestD) { bestD = d; best = p; }
        }
        return best;
    }
}
