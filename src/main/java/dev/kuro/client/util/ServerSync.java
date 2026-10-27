package dev.kuro.client.util;

/**
 * Keeps automation in step with what the server has actually acknowledged, instead of fixed timers.
 *
 * Two rules:
 *  1. ONE interaction step per client (game) tick. A step is a slot change, an attack, or a swap back. Two steps never
 *     share a simulation frame, so the server never has to resolve several transaction ends inside one tick.
 *  2. After a step, the next one waits for the server's transaction (the ping the server sends and the client answers
 *     with a pong) to come back. If the server does not send transactions at all, or one is overdue, it falls back to
 *     "next tick" / the timeout so nothing stalls.
 *
 * A teleport or knockback from the server also holds the current tick: the state the step was planned on is stale.
 */
public final class ServerSync {
    private static volatile int pings;          // transactions received (network -> game thread handoff is done by the mixin TAIL)
    private static int tick;
    private static int lastStepTick = -1000;
    private static int stepPings;               // ping counter when the last step was taken
    private static int lastPingTick = -1000;
    private static int lastPingCount;
    private static int holdTick = -1000;        // tick in which the server changed our state under us

    /** The server is considered to run transactions if one arrived within this many ticks. */
    private static final int TRANSACTION_WINDOW = 40;

    private ServerSync() {}

    /** Start of every client tick. */
    public static void nextTick() {
        tick++;
        int p = pings;
        if (p != lastPingCount) { lastPingCount = p; lastPingTick = tick; }
    }

    /** Server transaction (ping) received. Called from the network handler mixin on the game thread. */
    public static void onTransaction() { pings++; }

    /** Server teleported us / changed our velocity: whatever was planned this tick is based on old state. */
    public static void onServerStateChange() { holdTick = tick; }

    public static boolean transactionsActive() { return tick - lastPingTick < TRANSACTION_WINDOW; }

    /**
     * True when a new interaction step may be taken now: none yet this tick, the server did not move us this tick, and
     * the previous step has been confirmed by a server transaction (or the timeout passed).
     *
     * @param timeoutTicks how long to wait for a transaction before giving up on it (lag spike safety)
     */
    public static boolean ready(int timeoutTicks) {
        if (lastStepTick == tick) return false;
        if (holdTick == tick) return false;
        return confirmed(timeoutTicks);
    }

    /** Previous step acknowledged by the server (a transaction came in after it), or the wait ran out. */
    public static boolean confirmed(int timeoutTicks) {
        if (lastStepTick == -1000) return true;
        if (tick <= lastStepTick) return false;                       // always at least one full tick between steps
        if (!transactionsActive()) return true;                       // vanilla-style server: tick spacing is all there is
        if (pings != stepPings) return true;                          // a transaction arrived since the step
        return tick - lastStepTick >= Math.max(1, timeoutTicks);
    }

    /** Marks this tick's step as taken. */
    public static void beginStep() {
        lastStepTick = tick;
        stepPings = pings;
    }

    /** True if a step was already taken in the current tick. */
    public static boolean steppedThisTick() { return lastStepTick == tick; }

    /** Forget everything (world change / module off). */
    public static void reset() {
        lastStepTick = -1000;
        holdTick = -1000;
    }
}
