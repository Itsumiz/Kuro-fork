package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.TickPhase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Packet order for every attack the client makes itself (Trigger Bot, Kill Aura, Silent Aura, Auto Clicker, Fast Crystal,
 * Auto Dtap, Auto Shield Break). Every hit sends exactly one swing packet and one attack packet:
 *  - Swing before attack OFF (default): attack packet, then swing packet (what the 1.21 vanilla client does).
 *  - Swing before attack ON: swing packet, then attack packet (the old 1.8 order).
 *
 * Packet timing: a vanilla client sends attacks while it handles input, before that tick's movement packet. Most
 * modules decide at the END of the tick, after the movement packet is already out. With "Send before movement packet"
 * ON (default) such a hit is held back and sent at the very start of the next tick (before anything else is sent), so
 * the attack never trails the movement packet of the same tick. At most one attack per target per tick is queued.
 *
 * It is a settings-only module: the switches work whether or not the module is toggled on. Your own mouse clicks are
 * sent by the game itself and always use the vanilla order.
 */
public class AttackPackets extends Module {
    private static AttackPackets instance;
    private final Setting.Bool swingFirst = add(new Setting.Bool("Swing before attack", false));
    private final Setting.Bool beforeMove = add(new Setting.Bool("Send before movement packet", true));

    private static final int MAX_QUEUED = 6;
    private static final double MAX_REACH = 6.0;     // a held-back hit is dropped if the target moved farther than this

    /** action == null: a plain hit. Otherwise the action performs the whole packet sequence itself (see {@link #sequence}). */
    private record Pending(Entity target, Predicate<Entity> check, Consumer<Entity> action) {}
    private static final List<Pending> queue = new ArrayList<>();

    public AttackPackets() { super("Attack Packets", Category.COMBAT); instance = this; }

    public static boolean swingFirst() { return instance != null && instance.swingFirst.value; }

    private static boolean holdBack() {
        return (instance == null || instance.beforeMove.value) && TickPhase.afterMovement();
    }

    /** One hit: swing + attack in the chosen order. The weapon is whatever is in your hand (Auto Weapon picks it ahead of time). */
    public static void hit(Entity target) { submit(target, null); }

    /** Same, but a hit that is held back is re-checked right before it is sent and dropped if "check" says no. */
    public static void hit(Entity target, Predicate<Entity> check) { submit(target, check); }

    /**
     * A multi-step packet sequence against one target (e.g. axe attack, slot change, mace attack, slot back) that must go
     * out together. It is held back to the start of the next tick like a normal hit, then "action" runs once, in one go,
     * and is expected to call {@link #sendNow} for each attack. It replaces a plain hit queued for the same target.
     */
    public static void sequence(Entity target, Predicate<Entity> check, Consumer<Entity> action) {
        if (mc.player == null || mc.gameMode == null || target == null || action == null) return;
        if (holdBack()) {
            queue.removeIf(p -> p.target == target);
            if (queue.size() < MAX_QUEUED) queue.add(new Pending(target, check, action));
            return;
        }
        action.accept(target);
    }

    private static void submit(Entity target, Predicate<Entity> check) {
        if (mc.player == null || mc.gameMode == null || target == null) return;
        if (holdBack()) {
            for (Pending p : queue) if (p.target == target) return;     // one attack per target per tick
            if (queue.size() < MAX_QUEUED) queue.add(new Pending(target, check, null));
            return;
        }
        send(target);
    }

    /** Start of a client tick (before the movement packet): sends the hits that were held back. */
    public static void flush() {
        if (queue.isEmpty()) return;
        List<Pending> todo = new ArrayList<>(queue);
        queue.clear();
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.screen != null) return;
        for (Pending p : todo) {
            Entity e = p.target;
            if (!e.isAlive() || e.isRemoved() || mc.player.distanceTo(e) > MAX_REACH) continue;
            if (p.check != null && !p.check.test(e)) continue;
            if (p.action != null) p.action.accept(e); else send(e);
        }
    }

    /** Forget held-back hits (world change, module off...). */
    public static void clear() { queue.clear(); }

    /** Silent Aura aiming at this target: tell the server those angles right before the hit (camera untouched). */
    public static void sendSilentLook(Entity target) {
        if (!dev.kuro.client.util.Rotations.active() || mc.player == null) return;
        if (dev.kuro.client.util.CombatReach.rayHits(target, dev.kuro.client.util.Rotations.yaw(),
                dev.kuro.client.util.Rotations.pitch(), dev.kuro.client.util.CombatReach.MAX))
            dev.kuro.client.util.Rotations.sendLookPacket(true);
    }

    /** Swing + attack right now, in the chosen order (used by sequences; no hold-back, no queue). */
    public static void sendNow(Entity target) { if (mc.player != null && mc.gameMode != null && target != null) send(target); }

    private static void send(Entity target) {
        sendSilentLook(target);
        boolean first = swingFirst();
        if (first) mc.player.swing(InteractionHand.MAIN_HAND);
        mc.gameMode.attack(mc.player, target);
        if (!first) mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
