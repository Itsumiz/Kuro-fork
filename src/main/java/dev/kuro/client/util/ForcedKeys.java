package dev.kuro.client.util;

import net.minecraft.client.KeyMapping;

import java.util.HashSet;
import java.util.Set;

/** Keys that modules want the game to treat as held down (read by KeyBindingMixin). */
public final class ForcedKeys {
    private static final Set<KeyMapping> KEYS = new HashSet<>();
    private static final Set<KeyMapping> SUPPRESSED = new HashSet<>();
    private ForcedKeys() {}

    public static void force(KeyMapping k) { KEYS.add(k); }
    public static void release(KeyMapping k) { KEYS.remove(k); }
    public static boolean isForced(KeyMapping k) { return KEYS.contains(k); }

    /** Keys the game should read as released (wins over force). Used for taps: the real key state is untouched. */
    public static void suppress(KeyMapping k) { SUPPRESSED.add(k); }
    public static void unsuppress(KeyMapping k) { SUPPRESSED.remove(k); }
    public static boolean isSuppressed(KeyMapping k) { return SUPPRESSED.contains(k); }
}
