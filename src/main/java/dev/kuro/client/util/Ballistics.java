package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Client-side flight simulation for thrown / shot projectiles (arrows, bolts, ender pearls). Vanilla order per tick:
 * move by the current velocity (stopping at the first block / entity on the way), then velocity *= drag, then
 * velocity.y -= gravity. Arrows: gravity 0.05, drag 0.99. Ender pearls: gravity 0.03, drag 0.99.
 */
public final class Ballistics {
    private Ballistics() {}

    public static final class Result {
        /** Start point first, then the position after every simulated tick; the last point is the impact point. */
        public final List<Vec3> pts = new ArrayList<>();
        public Vec3 end;
        /** Face of the block that was hit, or null when the path ended in the air / on an entity. */
        public Direction face;
        /** First living entity the path crosses before any block, or null. */
        public LivingEntity hit;
        public boolean hitBlock;
        public int ticks;
    }

    public static Result simulate(Entity ignore, Vec3 start, Vec3 velocity, double gravity, double drag,
                                  int maxTicks, boolean checkEntities) {
        Minecraft mc = Minecraft.getInstance();
        Result r = new Result();
        if (mc.level == null) { r.end = start; r.pts.add(start); return r; }
        Vec3 pos = start, vel = velocity;
        r.pts.add(pos);
        for (int t = 1; t <= maxTicks; t++) {
            Vec3 next = pos.add(vel);
            BlockHitResult bhr = mc.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ignore));
            if (bhr.getType() != HitResult.Type.MISS) {
                r.pts.add(bhr.getLocation());
                r.face = bhr.getDirection();
                r.hitBlock = true;
                r.ticks = t;
                break;
            }
            r.pts.add(next);
            pos = next;
            r.ticks = t;
            vel = vel.scale(drag).subtract(0, gravity, 0);
            if (pos.y < -100) break;
        }
        if (checkEntities) findEntityHit(ignore, r);
        r.end = r.pts.get(r.pts.size() - 1);
        return r;
    }

    /** Cuts the path at the first living entity it crosses (same 0.3 block margin vanilla uses for projectiles). */
    private static void findEntityHit(Entity ignore, Result r) {
        Minecraft mc = Minecraft.getInstance();
        if (r.pts.size() < 2) return;
        double x1 = Double.MAX_VALUE, y1 = Double.MAX_VALUE, z1 = Double.MAX_VALUE, x2 = -Double.MAX_VALUE, y2 = -Double.MAX_VALUE, z2 = -Double.MAX_VALUE;
        for (Vec3 p : r.pts) {
            x1 = Math.min(x1, p.x); y1 = Math.min(y1, p.y); z1 = Math.min(z1, p.z);
            x2 = Math.max(x2, p.x); y2 = Math.max(y2, p.y); z2 = Math.max(z2, p.z);
        }
        AABB all = new AABB(x1, y1, z1, x2, y2, z2).inflate(2.0);
        List<Entity> near = mc.level.getEntities(ignore, all,
                e -> e instanceof LivingEntity le && le.isAlive() && !le.isSpectator() && e != ignore);
        if (near.isEmpty()) return;
        for (int i = 1; i < r.pts.size(); i++) {
            Vec3 a = r.pts.get(i - 1), b = r.pts.get(i);
            Entity best = null;
            Vec3 bestPoint = null;
            double bestD = Double.MAX_VALUE;
            for (Entity e : near) {
                AABB box = e.getBoundingBox().inflate(0.3);
                Vec3 hit = null;
                if (box.contains(a)) hit = a;
                else {
                    Optional<Vec3> c = box.clip(a, b);
                    if (c.isPresent()) hit = c.get();
                }
                if (hit == null) continue;
                double d = hit.distanceToSqr(a);
                if (d < bestD) { bestD = d; best = e; bestPoint = hit; }
            }
            if (best != null) {
                while (r.pts.size() > i) r.pts.remove(r.pts.size() - 1);
                r.pts.add(bestPoint);
                r.hit = (LivingEntity) best;
                r.face = null;
                r.hitBlock = false;
                r.ticks = i;
                return;
            }
        }
    }
}
