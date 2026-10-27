package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Backtrack. Holds back the server's position updates for nearby players for a short time (Delay), so the players you
 * see and aim at are where they were a moment ago. The held updates are handed to the game in their original order
 * once the delay has passed, the player moves out of Range, or the module is switched off, so nobody is ever lost or
 * left in the wrong place.
 *
 * Only incoming movement of OTHER players is delayed (move, teleport and position-sync packets). Nothing about your
 * own packets changes. Hooked from ClientPlayNetworkHandlerMixin.
 *
 * Mode "Ghost hitbox" (default): nothing is held back, so players move on screen exactly as normal, but the box your
 * crosshair aims at (and the box the Hitboxes module draws) stays where that player was Delay ms ago. This is purely
 * client side: it changes what YOU can click on. The server still decides whether a hit counts. The aim hook is
 * ProjectileUtilMixin; the position history is recorded here every tick.
 */
public class Backtrack extends Module {
    private static Backtrack instance;

    private final Setting.Num mode = add(new Setting.Num("Mode", 0, 0, 1, 1).labelled("Ghost hitbox", "Delay packets"));
    private final Setting.Num delay = add(new Setting.Num("Delay (ms)", 100, 20, 500, 10));
    private final Setting.Num range = add(new Setting.Num("Range", 6.0, 3.0, 12.0, 0.5));
    private final Setting.Bool skipFriends = add(new Setting.Bool("Skip friends", true));

    private record Held(ClientPacketListener listener, Packet<?> packet, int id, long at) {}
    private record Snap(long t, double x, double y, double z) {}

    /** Ghost mode: where each nearby player was over the last Delay + 300 ms, oldest first. */
    private static final Map<Integer, ArrayDeque<Snap>> HISTORY = new HashMap<>();

    private static final ArrayDeque<Held> QUEUE = new ArrayDeque<>();
    private static boolean releasing;     // true while WE hand a held packet back, so the mixin lets it through

    public Backtrack() {
        super("Backtrack", Category.COMBAT);
        instance = this;
    }

    /** Cheap check for the mixins (runs for every movement packet): is the module on and are we on the game thread? */
    public static boolean active() {
        return instance != null && instance.isEnabled() && instance.mode.intValue() == 1 && !releasing && mc.isSameThread();
    }

    private boolean ghostMode() { return mode.intValue() == 0; }

    /**
     * Ghost mode: the box of this player where it was Delay ms ago (the box the crosshair / Hitboxes use), or null when
     * the entity should use its real box (module off, other mode, not a tracked player, no history yet).
     */
    public static AABB ghostBox(Entity e) {
        Backtrack b = instance;
        if (b == null || !b.isEnabled() || !b.ghostMode() || !(e instanceof Player) || e == mc.player) return null;
        ArrayDeque<Snap> q = HISTORY.get(e.getId());
        if (q == null || q.isEmpty()) return null;
        long cutoff = System.currentTimeMillis() - (long) b.delay.value;
        Snap pick = null;
        for (Snap s : q) {                       // newest snapshot that is at least Delay old
            if (s.t() <= cutoff) pick = s; else break;
        }
        if (pick == null) pick = q.peekFirst();  // not enough history yet: the oldest we have
        return e.getBoundingBox().move(pick.x() - e.getX(), pick.y() - e.getY(), pick.z() - e.getZ());
    }

    /** Once per client tick: remember where every tracked player is right now. */
    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null || !ghostMode()) { HISTORY.clear(); return; }
        long now = System.currentTimeMillis(), keep = (long) delay.value + 300L;
        double inR = range.value * range.value, outR = Math.pow(range.value + 2.0, 2);
        HISTORY.keySet().removeIf(id -> {        // left range / died / left the world
            Entity e = mc.level.getEntity(id);
            return !(e instanceof Player p) || !p.isAlive() || mc.player.distanceToSqr(p) > outR;
        });
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;
            if (skipFriends.value && Friends.shield(p)) { HISTORY.remove(p.getId()); continue; }
            if (mc.player.distanceToSqr(p) > inR) continue;
            ArrayDeque<Snap> q = HISTORY.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
            Snap last = q.peekLast();
            if (last != null && Math.abs(last.x() - p.getX()) + Math.abs(last.y() - p.getY()) + Math.abs(last.z() - p.getZ()) > 8.0) q.clear();   // teleport: no ghost across it
            q.addLast(new Snap(now, p.getX(), p.getY(), p.getZ()));
            while (q.size() > 1 && now - q.peekFirst().t() > keep) q.pollFirst();
        }
    }

    /**
     * Called for each incoming entity-position packet on the game thread. Returns true = the packet was taken and the
     * caller must cancel it (it comes back through release()).
     */
    public static boolean intercept(ClientPacketListener listener, Packet<?> packet, int id) {
        if (!active() || mc.level == null || mc.player == null) return false;
        if (!holdingFor(id) && !relevant(id)) return false;       // keep order: once an entity has a held packet, all its later ones queue too
        QUEUE.add(new Held(listener, packet, id, System.currentTimeMillis()));
        return true;
    }

    private static boolean holdingFor(int id) {
        for (Held h : QUEUE) if (h.id == id) return true;
        return false;
    }

    /** A player other than you, within Range (and not a protected friend). */
    private static boolean relevant(int id) {
        if (mc.level == null || mc.player == null) return false;
        Entity e = mc.level.getEntity(id);
        if (!(e instanceof Player p) || p == mc.player || !p.isAlive()) return false;
        if (instance.skipFriends.value && Friends.shield(p)) return false;
        double r = instance.range.value;
        return mc.player.distanceToSqr(p) <= r * r;
    }

    @Override public boolean early() { return true; }

    @Override
    public void onPreTick() {
        if (mc.level == null || mc.player == null) { QUEUE.clear(); return; }
        long now = System.currentTimeMillis(), maxAge = (long) delay.value;
        Iterator<Held> it = QUEUE.iterator();
        // The queue is in arrival order, so for any one entity its packets are released oldest first.
        while (it.hasNext()) {
            Held h = it.next();
            // out of range (+2 blocks so it does not flicker at the edge): its updates go through at once
            Entity e = mc.level.getEntity(h.id);
            boolean gone = !(e instanceof Player) || mc.player.distanceToSqr(e) > Math.pow(range.value + 2.0, 2);
            if (gone || now - h.at >= maxAge) { it.remove(); release(h); }
        }
    }

    @Override
    protected void onDisable() { flush(); HISTORY.clear(); }

    /** Hands every held packet back to the game, in order. */
    public static void flush() {
        Held h;
        while ((h = QUEUE.poll()) != null) release(h);
    }

    private static void release(Held h) {
        releasing = true;
        try {
            if (h.packet instanceof ClientboundMoveEntityPacket m) h.listener.handleMoveEntity(m);
            else if (h.packet instanceof ClientboundTeleportEntityPacket t) h.listener.handleTeleportEntity(t);
            else if (h.packet instanceof ClientboundEntityPositionSyncPacket s) h.listener.handleEntityPositionSync(s);
        } catch (RuntimeException ignored) {
            // the entity can be gone by now; nothing to apply
        } finally {
            releasing = false;
        }
    }
}
