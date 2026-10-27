package dev.kuro.client.module;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which modules count as "blatant / detected".
 *
 * The Glass menu shows a warning before you ENABLE or KEYBIND any module marked true here.
 *
 * To change a module, edit its line below: true = warn, false = no warning.
 * The name must match the module's name in the menu (case does not matter).
 * A module that is not listed here never warns, so a new module is safe until you add it.
 */
public final class BlatantModules {
    private BlatantModules() {}

    private static final Map<String, Boolean> STATUS = new HashMap<>();
    private static void s(String module, boolean blatant) { STATUS.put(module.toLowerCase(Locale.ROOT), blatant); }

    static {
        // ---- Combat ----
        s("Kill Aura", true);
        s("Silent Aura", true);
        s("Attack Aura", true);
        s("Attack Packets", true);
        s("Backtrack", true);
        s("Crystal Aura", true);
        s("Simple Crystal Aura", true);
        s("Fast Crystal", true);
        s("Auto Dtap", true);
        s("Auto Mace", false);
        s("Auto Anchor", true);
        s("Anchor Bomb", true);
        s("Safe Anchor", true);
        s("Crystal Dtap", true);
        s("Auto Clicker", true);
        s("Criticals", true);
        s("Velocity", true);
        s("Reach", true);
        s("Hitbox Expand", true);
        s("Projectile Aim", true);
        s("Trigger Bot", false);
        s("Aim Assist", false);
        s("Anti Bot", false);
        s("Breach Swap", false);
        s("Spear Swap", false);
        s("Auto Weapon", false);
        s("Auto Shield Break", false);
        s("Auto Box", false);
        s("Auto Cobweb", false);
        s("Surround", false);
        s("Jump Reset", false);
        s("WTap", false);
        s("Auto Totem", false);
        s("Auto Armor", false);
        s("Totem Pops", false);
        s("Hit Sounds", false);

        // ---- Movement ----
        s("Flight", true);
        s("Elytra Fly", true);
        s("Elytra Hop", true);
        s("Bhop", true);
        s("Scaffold", false);
        s("Entity Speed", true);
        s("No Slow", true);
        s("No Fall", true);
        s("Freeze", true);
        s("Keep Sprint", true);
        s("Sprint", false);
        s("Safe Walk", false);
        s("Auto Walk", false);
        s("Baritone", false);
        s("Auto Build", false);

        // ---- Player ----
        s("Chest Stealer", true);
        s("Hover Totem", false);
        s("Freecam", false);
        s("Auto Eat", false);
        s("Auto Tool", false);
        s("Auto Fish", false);
        s("Auto MLG", false);
        s("Auto Tunnel", false);
        s("Auto Replenish", false);
        s("Inv Sorter", false);
        s("Elytra Swap", false);
        s("Pearl Catch", false);
        s("Ding Dong Builder", false);
        s("Macro", false);
        s("Macro Record", false);
        s("Toggle All", false);
        s("Radial Menu", false);

        // ---- Render ----
        s("Hitboxes", false);
        s("Tracers", false);
        s("Bow Aim", false);
        s("Pearl Finder", false);
        s("Player Indicator", false);
        s("Jump Bubbles", false);
        s("Player Trails", false);
        s("Reach Circle", false);
        s("Player Info", false);
        s("Fullbright", false);
        s("Freelook", false);
        s("Waypoints", false);
        s("Add Waypoint", false);
        s("Knife", false);
        s("Sky", false);
        s("Projectile Trails", false);
        s("Break Progress", false);
        s("Weather Control", false);
        s("Snowflakes", false);
        s("Spotify", false);
        s("HUD", false);
        s("Glass UI", false);
        s("Blob UI", false);
        s("Sidebar UI", false);

        // ---- Base finding ----
        s("Block ESP", false);
        s("Sus Chunk Finder", false);
        s("Stash Finder", false);
        s("New Chunks", false);
        s("Trail Tracker", false);
        s("Logout Spots", false);
        s("Geode ESP", false);
        s("Ancient Debris", false);
        s("Light Debug", false);
        s("Storage ESP", false);
        s("Found List", false);

        // ---- Misc ----
        s("Friends", false);
        s("Config", false);
        s("Username Hider", false);
    }

    public static boolean isBlatant(Module m) {
        return m != null && STATUS.getOrDefault(m.name.toLowerCase(Locale.ROOT), false);
    }
}
