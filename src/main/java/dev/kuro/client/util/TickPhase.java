package dev.kuro.client.util;

/**
 * Tracks where we are inside a client tick. A vanilla client sends its attack / interact packets while it handles
 * input, i.e. BEFORE the movement packet of that tick. Modules that act at the end of the tick (after the movement
 * packet is out) would put the attack packet on the wire after the movement packet, a packet order a real client
 * never produces. AttackPackets uses this to hold such hits back until the start of the next tick.
 */
public final class TickPhase {
    private static boolean moved;

    private TickPhase() {}

    /** Start of a client tick: nothing has been sent yet. */
    public static void startTick() { moved = false; }

    /** The movement packet for this tick has been built / sent. */
    public static void markMoved() { moved = true; }

    /** True once this tick's movement packet is out: attack packets sent now would be out of order. */
    public static boolean afterMovement() { return moved; }
}
