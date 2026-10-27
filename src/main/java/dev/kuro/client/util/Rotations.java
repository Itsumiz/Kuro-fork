package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;

/**
 * Silent (server-side) rotation without contaminating movement packets.
 *
 * Grim (and vanilla physics simulation) flags PosRot packets whose look direction
 * disagrees with the position delta. So we NEVER put silent yaw/pitch into the
 * regular movement packet. Instead:
 *   - Movement packets always carry your real camera angles (WASD stays vanilla).
 *   - When a module needs the server to raycast a different direction, it calls
 *     {@link #sendLookPacket()} which injects a pure LookAndOnGround packet.
 *
 * Client-side look vector (ray casts / crosshair helpers) still follows the silent
 * angles via EntityMixin#getRotationVec while active.
 */
public final class Rotations {
    private static Object owner;
    /** The module (if any) whose silent angles should also be shown on YOUR player model in third person (F5). */
    public static Object modelOwner;
    private static boolean active;
    private static float yaw, pitch;
    private static float lastSentYaw = Float.NaN, lastSentPitch = Float.NaN;
    private static Constructor<?> lookCtor;

    private Rotations() {}

    public static boolean active() { return active; }
    public static float yaw() { return yaw; }
    public static float pitch() { return pitch; }
    public static boolean ownedBy(Object o) { return owner == o; }
    public static boolean free() { return owner == null; }

    /** Takes (or keeps) ownership and sets the silent angles. False when somebody else owns it. */
    public static boolean set(Object who, float newYaw, float newPitch) {
        if (owner != null && owner != who) return false;
        owner = who;
        active = true;
        yaw = newYaw;
        pitch = Mth.clamp(newPitch, -90f, 90f);
        return true;
    }

    /** Gives the rotation back (only if "who" owns it). */
    public static void release(Object who) {
        if (owner != who) return;
        owner = null;
        active = false;
        if (modelOwner == who) modelOwner = null;
        lastSentYaw = Float.NaN;
        lastSentPitch = Float.NaN;
    }

    /** True while the silent angles should be drawn on your own model (third person) - see SilentModelMixin. */
    public static boolean showOnModel() { return active && modelOwner != null && owner == modelOwner; }

    public static Vec3 viewVector() { return Vec3.directionFromRotation(pitch, yaw); }

    /**
     * Injects a ServerboundMovePlayerPacket.Rot with the current silent angles.
     * Call this AFTER the tick's movement packet and BEFORE an attack so the server
     * raycast uses these angles without tying them to a position update (Grim-safe).
     *
     * @return true if a packet was sent (or angles were already the last ones sent)
     */
    public static boolean sendLookPacket() { return sendLookPacket(false); }

    private static boolean restorePending;

    /** True once after a forced silent look packet: the next movement packet must carry the real camera angles again. */
    public static boolean consumeRestore() { boolean r = restorePending; restorePending = false; return r; }

    /**
     * force = send even when these angles were already sent earlier. Needed right before an attack: the movement
     * packets in between carry the real camera angles, so "last sent" no longer matches what the server holds.
     */
    public static boolean sendLookPacket(boolean force) {
        if (!active) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;

        // skip if we already told the server these exact angles this session
        if (!force && !Float.isNaN(lastSentYaw)
                && Math.abs(Mth.wrapDegrees(yaw - lastSentYaw)) < 0.01f
                && Math.abs(pitch - lastSentPitch) < 0.01f) {
            return true;
        }

        try {
            if (lookCtor == null) {
                for (Constructor<?> c : ServerboundMovePlayerPacket.Rot.class.getConstructors()) {
                    Class<?>[] t = c.getParameterTypes();
                    if (t.length >= 3 && t[0] == float.class && t[1] == float.class && t[2] == boolean.class) {
                        lookCtor = c;
                        break;
                    }
                }
            }
            if (lookCtor == null) return false;
            Object[] a = new Object[lookCtor.getParameterCount()];
            a[0] = yaw;
            a[1] = pitch;
            for (int i = 2; i < a.length; i++) a[i] = Boolean.FALSE;
            if (mc.player.onGround() && a.length > 2) a[2] = Boolean.TRUE;
            mc.getConnection().send((Packet<?>) lookCtor.newInstance(a));
            lastSentYaw = yaw;
            lastSentPitch = pitch;
            restorePending = true;
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
