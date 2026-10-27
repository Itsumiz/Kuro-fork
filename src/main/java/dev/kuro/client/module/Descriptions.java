package dev.kuro.client.module;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** One-line descriptions shown under the module names in the sidebar menu. Modules without an entry just show their name. */
public final class Descriptions {
    private Descriptions() {}
    private static final Map<String, String> MAP = new HashMap<>();
    private static void d(String name, String text) { MAP.put(name.toLowerCase(Locale.ROOT), text); }

    static {
        // Render
        d("HUD", "UI theme for every menu, plus colour, rounding, blur, watermark and module list");
        d("Sidebar UI", "Switches between the classic windows and this sidebar menu");
        d("Glass UI", "Frosted glass menu with shader effects and spring animations. Off = the older menu");
        d("Blob UI", "Glowing blob hub menu with a glass panel per category");
        d("Damage Numbers", "Floating numbers over anything that loses health, coloured by how hard the hit was. Optional healing numbers, only-my-hits filter, size, rise and lifetime. Client-side only");
        d("Hitboxes", "Filled 3D hitbox boxes around players, with a custom colour, through walls");
        d("Tracers", "Lines from your crosshair to every loaded player");
        d("Bow Aim", "Shows where your arrow will land while you draw a bow or hold a loaded crossbow: flight path, landing marker and the player it would hit");
        d("Pearl Finder", "Highlights enemy ender pearls through walls with their thrower, distance and predicted landing spot");
        d("Player Indicator", "Arrows in a ring around your crosshair pointing toward every player, with name and distance. Optional head markers");
        d("Player Trails", "Cosmetic trail behind you (and optionally other players): ribbon, comet, helix, sparkles, embers, hearts, stars, snow or ground rings. Colours can follow your UI theme");
        d("Jump Bubbles", "Glowing rings spread over the ground where a player jumps, then fade out");
        d("Reach Circle", "Glowing circle on the ground around every player's feet showing how far they can hit; turns red when you are inside it");
        d("Nametags", "Name plate with health bar, exact health, armor and the real-world clock time; replaces vanilla tags");
        d("Fake Player", "Client-side dummy player for testing combat modules: takes simulated hits, knockback and dies, nothing is sent to the server");
        d("Player Info", "Name, ping, health and armor above players");
        d("Fullbright", "Full brightness everywhere");
        d("Radial Menu", "A radial menu with three slices; click a slice to send its text or command");
        d("Auto Tool", "Picks the fastest hotbar tool for the block you are mining");
        d("Auto Fish", "Casts, reels in on a bite and casts again while you hold a fishing rod");
        d("Username Hider", "Shows a fake name instead of your username on your screen");
        d("Freelook", "Look around freely while your character keeps facing (and walking) the same way. Bind a key: hold it to look around, let go and the camera glides back. Third-person pull-back, camera distance and the return speed are settings");
        d("Zoom", "Hold the key to zoom in (default C), scroll to change how far. Eased in and out, with a slower mouse while zoomed so aiming stays steady");
        d("Item ESP", "Outlines dropped items through walls with name, count and distance. Valuable items (a list you edit) get their own colour; nearby stacks of the same item share one label");
        d("Performance", "Small HUD with FPS, 1% low, frame time, ping, server TPS, memory and entity count. Can also drop the frame rate while the window is not in focus. Drag it with .hudedit");
        d("Stopwatch", "HUD stopwatch or countdown with a chime when time is up. Start / pause / reset from the menu or with .timer");
        d("Waypoints", "Saved coordinates drawn on screen with distance");
        d("Add Waypoint", "Saves a waypoint at your feet when its key is pressed");
        d("Snowflakes", "Snow falls down the screen while the menu is open");
        d("Kill Whack", "When you kill a player, a little robot jumps in and whacks the body with a mallet (client side only)");
        d("WTap", "Resets sprint after a hit by tapping the forward (or back) key for a tick or two, through the normal input path");
        d("Sky", "Custom sky, cloud, fog and sunset colours (white, gray, any tint or a slow rainbow)");
        d("Projectile Trails", "Glowing trail behind arrows, pearls and tridents, with a predicted landing point");
        d("Break Progress", "Shows how far through the block you are mining");
        d("Weather Control", "Choose clear, rain or thunder on your client, whatever the server says");
        d("Spotify", "Shows the song Spotify is playing");
        d("Armor HUD", "Your armor (and optionally offhand / held item) with durability. Drag it with .hudedit");
        d("Potion HUD", "Your active potion effects with level and time left. Drag it with .hudedit");
        d("Keystrokes", "W A S D, mouse buttons with CPS and space bar that light up while pressed. Drag it with .hudedit");
        d("Coord Share", "Bind a key: copies your coordinates and/or posts them in chat. Also the .coords command");
        d("Anchor Aura", "Places a respawn anchor next to the closest player, charges it with glowstone and detonates it (echo client port). Click mode: press the Activate Key once; Hold mode: runs while it is held. Optional safety block between you and the anchor, auto aim (silent or visible), Grim-style rotation settle check. Not in the Nether");
        d("Anchor Bomb", "Place a respawn anchor and it fills it with glowstone and detonates it: one vanilla right-click per step, on your crosshair, no extra packets. Not in the Nether");
        d("Crystal Dtap", "One key on the nearest enemy: launches them (melee hit or wind charge), places obsidian under them, places two end crystals and breaks both in the same tick. Server-side aim. Needs a weapon or wind charge, obsidian and crystals");
        d("Safe Anchor", "One key: places an anchor as close to the nearest enemy as it can (else on your crosshair, else the nearest block to them), charges it, puts a glowstone block between you and it as cover, then detonates (even when there is no room for the cover). Aim moves server side only, smoothly. Needs an anchor and glowstone in the hotbar. Not in the Nether");
        d("Chat Mentions", "Highlights your name in chat, plays a ping and shows a toast when someone mentions you");
        d("Chat Timestamps", "Puts the real-world time in front of every chat line");
        d("Fake Loading", "A fake loading screen with a sound. While on, it plays the next time the game starts. Test plays it now, Esc skips it");
        d("Clock", "Real-world time on your screen. 12/24h, seconds, date and position are settings");
        d("Notifications", "A pop-up in the corner whenever a module is turned on or off");
        // Combat
        d("Silent Aura", "Kill aura that turns smoothly onto the nearest target (Aim Speed) and only hits when the crosshair is on it");
        d("Trigger Bot", "Attacks when the crosshair is on a player");
        d("Auto Clicker", "Clicks for you at a set rate while you hold attack on a target");
        d("Kill Aura", "Attacks the nearest player in range");
        d("Attack Aura", "Aura that turns toward the target nearest your crosshair and hits it. Rotation: 1 Default, 2 Snap, 3 HvH, 4 Camera");
        d("Simple Crystal Aura", "Breaks nearby crystals and places new ones on obsidian / bedrock near an enemy");
        d("Projectile Aim", "Aims bows, crossbows and tridents with target prediction and gravity compensation");
        d("Aim Assist", "Smoothly turns toward the target nearest your crosshair");
        d("Breach Swap", "Hits with a Breach mace from the hotbar, then swaps back");
        d("Auto Weapon", "Picks the best weapon for the target: axe on shields, mace when falling, stun slam");
        d("Spear Swap", "Selects a hotbar spear for the hit, then switches back");
        d("Auto Box", "Webs the nearest player, then boxes them in while they are stuck. Leaves one block open so you can hit them, and highlights every block it is about to place");
        d("Auto Cobweb", "Places a cobweb on the nearest player");
        d("Surround", "Places blocks on the four sides of your feet");
        d("Totem Pops", "Chat line and counter on every totem pop");
        d("Hit Sounds", "Plays a sound when your hit lands, and a different chime when it kills. Five sounds, volume, pitch and optional pitch variation. Audio only, nothing is sent to the server");
        d("Auto Totem", "Keeps a Totem of Undying in your offhand");
        d("Auto Armor", "Wears the best armor you carry");
        d("Auto Shield Break", "Axes a blocking player's shield, then switches back");
        d("Fast Crystal", "No crystal place delay and instant crystal breaking");
        d("Auto Dtap", "Crystal double tap with optional auto break");
        d("Criticals", "Small in-air packets so ground hits crit");
        d("Velocity", "Scales knockback down");
        d("Jump Reset", "Jumps the moment you are hit to cut knockback");
        d("Hitbox Expand", "Bigger hitboxes on other players for your aim, so you can hit off-centre");
        d("Backtrack", "Ghost hitbox: players move normally but the box you aim at (and Hitboxes draws) stays where they were a moment ago. Or hold their position updates back (Delay packets). Delay and range are adjustable");
        d("Reach", "Extends block and entity interaction range, with an optional hit chance");
        // Movement
        d("Flight", "Fly with WASD, jump and sneak");
        d("Boat Fly", "Flies the boat you sit in (WASD + jump) and gets out and back into it every few ticks");
        d("Bhop", "Auto-jump while moving");
        d("Scaffold", "Places blocks under your feet as you walk");
        d("Elytra Fly", "Elytra flight controls");
        d("Elytra Hop", "Hold W: hops, glides on your elytra, then jabs a hotbar spear just before landing so the lunge keeps your speed, and hops again. Needs an elytra worn and a spear in the hotbar");
        d("Entity Speed", "Sets the speed of the mount you are riding");
        d("Auto Walk", "Holds the forward key for you");
        d("No Slow", "No walking slowdown while eating or blocking");
        d("No Fall", "Cancels fall damage");
        d("No Jump Delay", "Removes the jump cooldown so holding space jumps every tick");
        d("Fast Place", "Shorter right-click repeat delay, optionally only while holding blocks");
        d("No Break Delay", "Removes the delay between breaking two blocks");
        d("Step", "Walk up taller blocks by raising your step height");
        d("No Web", "Move freely through cobwebs; jump goes up, sneak goes down");
        d("Safe Walk", "Stops you walking off drops bigger than the limit");
        d("Freeze", "Server sees you standing still while you move");
        d("Baritone", "Controls for the Baritone pathfinding mod");
        d("Auto Build", "Builds a schematic with Baritone and restocks from a refill chest you pick: walks over, opens it, takes only the blocks the schematic needs, walks back and carries on. Calm movement and a steady or smoothly eased camera. Opens closed doors / gates in its way and closes the sign editor after a sign is placed. Needs Baritone. .build for commands");
        // Player
        d("Freecam", "Fly the camera without moving your character");
        d("Auto Eat", "Eats the best food when hunger gets low");
        d("Pearl Catch", "Throws a pearl, then a wind charge that meets it in the air");
        d("Auto MLG", "Places water under you before a long fall");
        d("Auto Tunnel", "Digs a straight tunnel ahead of you");
        d("Auto Replenish", "Refills a hotbar slot when its stack runs out");
        d("Chest Stealer", "Shift-clicks everything out of an open container");
        d("Inv Sorter", "Sorts your inventory, biggest stacks first");
        d("Elytra Swap", "Wears the hotbar Elytra with the most durability");
        d("Ding Dong Builder", "Builds a small structure from hotbar blocks");
        d("Toggle All", "Master switch: everything off, then back on");
        d("Macro", "Plays back your recorded macro");
        d("Macro Record", "Records your keys, clicks and camera turns");
        // Base Finding
        d("Block ESP", "Draws chosen blocks through walls");
        d("Sus Chunk Finder", "Rates and highlights chunks that look built in");
        d("Stash Finder", "Prints coordinates where many chests sit together");
        d("New Chunks", "Shows chunks generated since the server last saw them");
        d("Trail Tracker", "Follows long thin trails of new chunks");
        d("Logout Spots", "Marks where players logged out");
        d("Geode ESP", "Finds geodes with many grown clusters");
        d("Storage ESP", "Outlines chests, barrels, shulkers, ender chests and more through walls, each type in its own colour");
        d("Light Debug", "Outlines unusual light sources through walls: torches, lanterns, glowstone, froglights, shroomlights, sea lanterns, lamps...");
        d("Ancient Debris", "Outlines ancient debris through walls (only what the server sends; anti-xray hides buried ore)");
        d("Found List", "Everything the finders have found, in one list");
        // Misc
        d("Friends", "Players protected from your combat modules");
        d("Config", "Save, load, export and import your settings");
    }

    public static String of(Module m) { return MAP.getOrDefault(m.name.toLowerCase(Locale.ROOT), ""); }
}
