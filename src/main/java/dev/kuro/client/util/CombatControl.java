package dev.kuro.client.util;

/**
 * Lightweight coordination so combat modules (AutoMace, SilentAura, KillAura, TriggerBot, etc.)
 * do not fight over attacks and temporary exclusive control.
 *
 * Rotation ownership stays in {@link Rotations}. This class only tracks who currently owns the
 * "exclusive attack window" (e.g. AutoMace mid-smash sequence, stun-slam follow-up).
 *
 * Priority is higher number = more important. A claim with lower priority is refused while a
 * higher (or equal) claim is active.
 */
public final class CombatControl {
    public static final int PRIORITY_AURA = 10;
    public static final int PRIORITY_TRIGGER = 15;
    public static final int PRIORITY_HITSWAP = 20;
    public static final int PRIORITY_SHIELD = 25;
    public static final int PRIORITY_AUTOMACE = 30;

    private static Object owner;
    private static int priority;
    private static int ticksLeft;

    private CombatControl() {}

    /** True when nobody holds the exclusive attack claim. */
    public static boolean free() {
        return owner == null || ticksLeft <= 0;
    }

    public static boolean ownedBy(Object who) {
        return owner == who && ticksLeft > 0;
    }

    /**
     * Try to claim exclusive attack control for up to {@code ticks} client ticks.
     * Returns false if a higher-or-equal priority claim is already active by someone else.
     */
    public static boolean claim(Object who, int prio, int ticks) {
        if (owner != null && owner != who && ticksLeft > 0 && priority >= prio) return false;
        owner = who;
        priority = prio;
        ticksLeft = Math.max(1, ticks);
        return true;
    }

    /** Extend an existing claim owned by {@code who}. No-op if not the owner. */
    public static void renew(Object who, int ticks) {
        if (owner != who) return;
        ticksLeft = Math.max(ticksLeft, ticks);
    }

    /** Release only if {@code who} currently owns the claim. */
    public static void release(Object who) {
        if (owner != who) return;
        owner = null;
        priority = 0;
        ticksLeft = 0;
    }

    /** Called once per client tick (from ModuleManager / AutoMace). */
    public static void tick() {
        if (ticksLeft > 0) {
            ticksLeft--;
            if (ticksLeft <= 0) {
                owner = null;
                priority = 0;
            }
        }
    }

    /** True when some other module currently holds exclusive control. */
    public static boolean blocked(Object who) {
        return owner != null && owner != who && ticksLeft > 0;
    }
}
