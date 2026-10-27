package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.util.Mth;

/**
 * Friends manager. While this module is ON, Kill Aura and Trigger Bot never target a friend, and Hitboxes /
 * Tracers draw them in the friend colour. The same list is editable with .friend add/remove/list.
 */
public class FriendsModule extends Module {
    private final Setting.Hue color = add(new Setting.Hue("Friend color", 0.40f).wheel(0.7f, 1f));
    private final Setting.Num range = add(new Setting.Num("Add-nearby range", 8, 2, 32, 1));

    public FriendsModule() {
        super("Friends", Category.MISC);
        // click a box, type the username, press Enter (Ctrl+V works too) - the player doesn't need to be nearby
        add(new Setting.Text("Type name to add", FriendsModule::addTyped));
        add(new Setting.Text("Type name to remove", FriendsModule::removeTyped));
        add(new Setting.Button("Add aimed player", () -> {
            Player p = aimed();
            if (p == null) { CommandManager.say("Look at a player first"); return; }
            String n = p.getName().getString();
            CommandManager.say(Friends.add(n) ? "Added " + n + " as a friend" : n + " is already a friend");
        }));
        add(new Setting.Button("Remove aimed player", () -> {
            Player p = aimed();
            if (p == null) { CommandManager.say("Look at a player first"); return; }
            String n = p.getName().getString();
            CommandManager.say(Friends.remove(n) ? "Removed " + n : n + " wasn't a friend");
        }));
        add(new Setting.Button("Add nearby players", () -> {
            if (mc.level == null || mc.player == null) return;
            int added = 0;
            for (Player p : mc.level.players()) {
                if (p == mc.player || mc.player.distanceTo(p) > range.value) continue;
                if (Friends.add(p.getName().getString())) added++;
            }
            CommandManager.say("Added " + added + " nearby player" + (added == 1 ? "" : "s"));
        }));
        add(new Setting.Button("Show friends", () -> {
            var l = Friends.list();
            CommandManager.say(l.isEmpty() ? "No friends yet." : "Friends (" + l.size() + "): " + String.join(", ", l));
        }));
        add(new Setting.Button("Clear friends", () -> { Friends.clear(); CommandManager.say("Friends list cleared"); }));
        setEnabled(true);                       // friend protection is on by default
        Friends.protectSource = this::isEnabled;
        Friends.colorSource = () -> 0xFF000000 | color.rgb();
    }

    /** Cleans up what was typed: drops a leading @ and rejects anything that cannot be a username. */
    private static String clean(String raw) {
        String n = raw.trim();
        if (n.startsWith("@")) n = n.substring(1);
        if (n.isEmpty() || n.length() > 24 || !n.matches("[A-Za-z0-9_.\\-]+")) {
            CommandManager.say("\"" + raw + "\" isn't a valid username");
            return null;
        }
        return n;
    }

    private static void addTyped(String raw) {
        String n = clean(raw);
        if (n != null) CommandManager.say(Friends.add(n) ? "Added " + n + " as a friend" : n + " is already a friend");
    }

    private static void removeTyped(String raw) {
        String n = clean(raw);
        if (n != null) CommandManager.say(Friends.remove(n) ? "Removed " + n : n + " wasn't a friend");
    }

    private static Player aimed() {
        return mc.hitResult instanceof EntityHitResult h && h.getEntity() instanceof Player p && p != mc.player ? p : null;
    }
}
