package dev.kuro.client.util;

/**
 * Spreads block placements over a chosen time. "Finish time (s)" is how long the whole action should take: with
 * N blocks to place the modules wait seconds / N between blocks and place one block at a time, so the last block
 * lands about "seconds" after the action starts. 0 seconds keeps the old behaviour (the module's own Delay and
 * Blocks/tick settings).
 */
public final class Pacing {
    private Pacing() {}

    public static boolean paced(double seconds) { return seconds > 0.0; }

    /** Ticks to wait before each placement. */
    public static int gap(double seconds, int totalBlocks, int legacyDelay) {
        if (!paced(seconds)) return legacyDelay;
        return (int) Math.max(1, Math.round(seconds * 20.0 / Math.max(1, totalBlocks)));
    }

    /** Blocks allowed per placement step: always one when paced. */
    public static int perStep(double seconds, int legacyPerTick) {
        return paced(seconds) ? 1 : legacyPerTick;
    }
}
