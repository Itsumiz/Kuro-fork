package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.mixin.InteractionManagerInvoker;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.Rotations;
import dev.kuro.client.util.ServerSync;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Pearl Catch: throws an ender pearl, then throws a wind charge so that the charge meets the pearl in the air.
 * Needs an Ender Pearl and a Wind Charge in the hotbar. Switch it on (or press its key): it works out the throw
 * angles and timing from where you are and how you are moving, does both throws, and switches itself off.
 *
 * Planning predicts both flights tick by tick: a pearl starts at 1.5 blocks/tick plus your own motion, loses
 * speed to drag (0.99) and drops by gravity (0.03/tick); a wind charge flies straight at 1.5 blocks/tick. A plan
 * is only used if the two paths pass within ~0.6 blocks of each other, so on a bad position it simply refuses.
 * The plan is made in the very tick the throws happen (not when you press the key), from your real per-tick
 * movement, because the server gives a thrown item your movement of the last tick (not your post-friction speed).
 *
 * Vanilla rules and packet order. Every throw is one ordinary "use item" action, taken at the start of the client tick
 * (onPreTick), where a real client handles input, i.e. before this tick's movement packet:
 *   1. hotbar slot packet (only if the slot changed)   2. look packet with the throw angles
 *   3. use-item packet (carries exactly those angles)  4. swing packet (only if the use result asks the client to swing)
 * then the real view angles are restored and the next movement packet carries them again.
 * Rules kept: one hotbar swap per tick (SwapLimit), one interaction step per tick (ServerSync), nothing while a screen is
 * open / the player is using another item / dead / spectating, and neither item is used while it is on cooldown (ender
 * pearl 20 ticks, wind charge 10 ticks). The charge is only thrown if the pearl throw was accepted (use result), and
 * the original slot is selected again the tick after the last throw. If the planning runs on state that is stale (the
 * server teleported or pushed you this tick) the step is held back to the next tick.
 */
public class PearlCatch extends Module {
    private static final double SPEED = 1.5;
    private static final double PEARL_DRAG = 0.99;
    private static final double PEARL_GRAVITY = 0.03;
    private static final double MAX_MISS_SQ = 0.25;     // 0.5 blocks
    private static final int FLIGHT_TICKS = 30;
    /** Both throws get a random spread of ~0.011 blocks/tick per axis: expected squared miss after t ticks is about 0.00076 * t^2. */
    private static final double SPREAD = 0.00076;
    private static final int GIVE_UP_TICKS = 80;

    private final Setting.Bool useCurrentAim = add(new Setting.Bool("Use current aim", false));
    private final Setting.Num chargeDelay = add(new Setting.Num("Charge delay (ticks)", 0, 0, 10, 1));
    private final Setting.Bool showChat = add(new Setting.Bool("Chat messages", true));

    private enum State { IDLE, THROW_PEARL, WAIT_CHARGE, RESTORE }
    private State state = State.IDLE;
    private Plan plan;
    private Vec3 mv = Vec3.ZERO;     // how far the player moved during the last tick (what the server hands to thrown items)
    private int meetTick;              // tick of the closest approach found by the last evaluate()
    private int pearlSlot = -1, chargeSlot = -1, savedSlot = -1, elapsed;

    public PearlCatch() { super("Pearl Catch", Category.PLAYER); }

    @Override
    protected void onEnable() {
        state = State.IDLE;
        Player p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) { setEnabled(false); return; }
        pearlSlot = BlockPlacer.findSlot(s -> s.is(Items.ENDER_PEARL));
        chargeSlot = BlockPlacer.findSlot(s -> s.is(Items.WIND_CHARGE));
        if (pearlSlot < 0 || chargeSlot < 0) { fail("needs an Ender Pearl and a Wind Charge in the hotbar"); return; }
        if (p.getCooldowns().isOnCooldown(p.getInventory().getItem(pearlSlot))) { fail("the Ender Pearl is on cooldown"); return; }
        if (p.getCooldowns().isOnCooldown(p.getInventory().getItem(chargeSlot))) { fail("the Wind Charge is on cooldown"); return; }
        savedSlot = BlockPlacer.selected();
        plan = null;
        elapsed = 0;
        state = State.THROW_PEARL;      // the plan is made at the moment of the throw (see onPreTick)
    }

    @Override
    protected void onDisable() {
        if (mc.player != null && savedSlot >= 0) {
            BlockPlacer.selectForce(savedSlot);
            if (mc.gameMode != null) ((InteractionManagerInvoker) mc.gameMode).kuro$syncSelectedSlot();
        }
        Rotations.release(this);
        state = State.IDLE;
        plan = null;
        pearlSlot = chargeSlot = savedSlot = -1;
    }

    /**
     * Runs at the start of the client tick, before input handling, physics and the movement packet: the same place in the
     * packet order where a real client's right-click goes out. Position and motion read here are exactly what the server
     * knows (last tick's movement packet), which is also what it uses for the thrown item.
     */
    @Override
    public void onPreTick() {
        Player p = mc.player;
        if (state == State.IDLE) return;
        if (p == null || mc.level == null || mc.gameMode == null || mc.screen != null || p.isSpectator() || !p.isAlive()) {
            setEnabled(false);
            return;
        }
        if (state == State.RESTORE) {       // one swap per tick, and a full tick after the last throw
            if (!ServerSync.confirmed(5)) return;
            if (savedSlot < 0 || BlockPlacer.select(savedSlot)) {
                ServerSync.beginStep();
                ((InteractionManagerInvoker) mc.gameMode).kuro$syncSelectedSlot();
                setEnabled(false);
            }
            return;
        }
        if (state == State.THROW_PEARL) {
            if (!ServerSync.ready(5)) return;   // one step per tick; not on a tick where the server moved us (plan would be stale)
            if (p.isUsingItem()) return;        // eating / blocking / drawing a bow: vanilla would not throw now either
            // plan from the state the server has when it handles the throw: the position of the last tick's movement
            // packet and the movement it implies (position now minus position one tick ago, both before this tick's physics)
            Vec3 now = p.getPosition(1.0f), before = p.getPosition(0.0f);
            mv = new Vec3(now.x - before.x, now.y - before.y, now.z - before.z);
            int delay = Math.max(1, chargeDelay.intValue());   // one swap per tick: the charge goes out at least a tick after the pearl
            plan = useCurrentAim.value ? currentAimPlan(p, delay) : upwardPlan(p, delay);
            if (plan == null) { fail("no good way to catch the pearl from here (stand still for a moment, or change the charge delay)"); return; }
            Throw r = fire(pearlSlot, plan.pearlYaw, plan.pearlPitch);
            if (r == Throw.LATER) return;       // a rule said "not this tick": plan again next tick
            if (r == Throw.REFUSED) { fail("the Ender Pearl throw was refused, so no Wind Charge was thrown"); return; }
            ServerSync.beginStep();
            elapsed = 0;
            state = State.WAIT_CHARGE;
            return;
        }
        // WAIT_CHARGE: the charge goes out at the start of the tick that is 'delay' ticks after the pearl's tick
        elapsed++;
        if (elapsed > GIVE_UP_TICKS) { state = State.RESTORE; return; }
        if (elapsed < plan.delay) return;
        if (p.isUsingItem()) return;
        Throw r = fire(chargeSlot, plan.chargeYaw, plan.chargePitch);
        if (r == Throw.LATER) return;           // on cooldown / swap not allowed / rotation busy: try again next tick (timing slips, plan may miss)
        ServerSync.beginStep();
        state = State.RESTORE;
    }

    private enum Throw { DONE, LATER, REFUSED }

    /**
     * One vanilla use-item action, in vanilla packet order: slot packet, look packet, use-item packet, swing packet.
     * LATER = a rule says not now (nothing was sent that would need undoing), REFUSED = the use was not accepted.
     */
    private Throw fire(int slot, float yaw, float pitch) {
        Player p = mc.player;
        ItemStack stack = p.getInventory().getItem(slot);
        if (stack.isEmpty() || p.getCooldowns().isOnCooldown(stack)) return Throw.LATER;   // cooldown: pearl 20 ticks, wind charge 10
        if (!Rotations.free() && !Rotations.ownedBy(this)) return Throw.LATER;             // another module is using the silent angles
        if (!BlockPlacer.select(slot)) return Throw.LATER;                                  // one swap per tick: not now

        // 1. slot packet, now, so it is on the wire before the look and use packets
        ((InteractionManagerInvoker) mc.gameMode).kuro$syncSelectedSlot();

        // 2. look packet with the throw angles; the use packet below carries the same angles
        if (!Rotations.set(this, yaw, pitch) || !Rotations.sendLookPacket(true)) {
            Rotations.release(this);
            return Throw.LATER;
        }
        float oldYaw = p.getYRot(), oldPitch = p.getXRot();
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90f, 90f));

        // 3. use-item packet
        InteractionResult result = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);

        // 4. swing packet, only when the result asks the client for it (what Minecraft.startUseItem does)
        if (result instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT) {
            p.swing(InteractionHand.MAIN_HAND);
        }

        // the screen never shows the throw angles; the next movement packet carries the real ones (Rotations.consumeRestore)
        p.setYRot(oldYaw);
        p.setXRot(oldPitch);
        Rotations.release(this);
        return result.consumesAction() ? Throw.DONE : Throw.REFUSED;
    }

    private void fail(String why) {
        if (showChat.value && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Pearl Catch] ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(why).withStyle(ChatFormatting.WHITE)), false);
        }
        setEnabled(false);
    }

    // ---------------------------------------------------------------- planning

    private record Plan(float pearlYaw, float pearlPitch, float chargeYaw, float chargePitch, int delay, double miss) {}

    /** Pearl goes up (a little forward/back), the charge goes up too and meets it. */
    private Plan upwardPlan(Player p, int delay) {
        float ref = p.getYRot();
        Vec3 v = mv;
        float[] pearlBases;
        if (v.x * v.x + v.z * v.z < 0.003) {
            pearlBases = new float[]{ref};
        } else {
            float moveYaw = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
            pearlBases = Math.abs(Mth.wrapDegrees(moveYaw - ref)) < 20f ? new float[]{ref} : new float[]{ref, moveYaw};
        }
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        for (float pPitch = -90f; pPitch <= -76f; pPitch += 0.5f) {
            for (float base : pearlBases) {
                for (float pOff = -20f; pOff <= 20f; pOff += 2.5f) {
                    float pYaw = base + pOff;
                    for (float cPitch = -90f; cPitch <= -86f; cPitch += 1f) {
                        for (float cOff = -20f; cOff <= 20f; cOff += 5f) {
                            float cYaw = ref + cOff;
                            double miss = evaluate(p, pYaw, pPitch, cYaw, cPitch, delay);
                            if (miss > MAX_MISS_SQ) continue;
                            // prefer a close meeting, an EARLY one (both throws get a small random spread that grows
                            // with flight time), and angles that stay near where you already face
                            double score = miss + SPREAD * meetTick * meetTick + Math.abs(Mth.wrapDegrees(cYaw - ref)) * 0.01
                                    + Math.abs(Mth.wrapDegrees(pYaw - ref)) * 0.003 + Math.abs(cPitch + 90f) * 0.03;
                            if (score < bestScore) { bestScore = score; best = new Plan(pYaw, pPitch, cYaw, cPitch, delay, miss); }
                        }
                    }
                }
            }
        }
        return best;
    }

    /** The charge goes where you are looking now; the pearl angle and the delay are searched. */
    private Plan currentAimPlan(Player p, int minDelay) {
        float cYaw = p.getYRot(), cPitch = p.getXRot();
        int first = Math.max(3, minDelay);
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        for (int delay = first; delay <= first + 12; delay++) {
            for (float pPitch = -90f; pPitch <= 60f; pPitch += 2.5f) {
                for (float off = -180f; off < 180f; off += 5f) {
                    float pYaw = cYaw + off;
                    double miss = evaluate(p, pYaw, pPitch, cYaw, cPitch, delay);
                    if (miss > MAX_MISS_SQ) continue;
                    double score = miss + SPREAD * meetTick * meetTick + Math.abs(off) * 0.0005 + delay * 0.0001;
                    if (score < bestScore) { bestScore = score; best = new Plan(pYaw, pPitch, cYaw, cPitch, delay, miss); }
                }
            }
        }
        return best;
    }

    /**
     * Smallest squared distance between the pearl and the charge once the charge is in flight, or +infinity when
     * the plan is not usable. Pearl is thrown now; the charge is thrown 'delay' ticks later from where you will be.
     */
    private double evaluate(Player p, float pYaw, float pPitch, float cYaw, float cPitch, int delay) {
        Vec3 pd = dir(pYaw, pPitch), cd = dir(cYaw, cPitch);
        Vec3 v = mv;
        boolean air = !p.onGround();

        // pearl: leaves 0.1 below the eyes, inherits your motion (vertical only while airborne)
        double px = p.getX(), py = p.getEyeY() - 0.1, pz = p.getZ();
        double pvx = pd.x * SPEED + v.x, pvy = pd.y * SPEED + (air ? v.y : 0.0), pvz = pd.z * SPEED + v.z;

        // where you (and so the charge) will be when the charge is thrown
        double[] o = originAfter(p, delay);
        double cx = o[0], cy = o[1], cz = o[2];
        double cvx = cd.x * SPEED + o[3], cvy = cd.y * SPEED + (air ? o[4] : 0.0), cvz = cd.z * SPEED + o[5];

        double closest = Double.POSITIVE_INFINITY;
        for (int t = 1; t <= delay + FLIGHT_TICKS; t++) {
            pvy -= PEARL_GRAVITY;
            pvx *= PEARL_DRAG; pvy *= PEARL_DRAG; pvz *= PEARL_DRAG;
            px += pvx; py += pvy; pz += pvz;
            if (t <= delay) continue;               // the charge is not in the air yet
            cx += cvx; cy += cvy; cz += cvz;
            double dx = px - cx, dy = py - cy, dz = pz - cz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < closest) { closest = d; meetTick = t; }
        }
        return closest;
    }

    /**
     * {x, eyeY, z, vx, vy, vz} of the player after 'ticks' ticks, assuming no input change. vx/vy/vz is the movement
     * during the last of those ticks (at 0 ticks: the last real tick), which is what a thrown item inherits.
     */
    private double[] originAfter(Player p, int ticks) {
        Vec3 v = mv;
        double x = p.getX(), y = p.getEyeY(), z = p.getZ(), vx = v.x, vy = v.y, vz = v.z;
        boolean air = !p.onGround();
        for (int i = 0; i < ticks; i++) {
            if (air) { vy = (vy - 0.08) * 0.98; vx *= 0.91; vz *= 0.91; y += vy; }
            else { vx *= 0.546; vz *= 0.546; }
            x += vx; z += vz;
        }
        return new double[]{x, y, z, vx, air ? vy : 0.0, vz};
    }

    private static Vec3 dir(float yaw, float pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }
}
