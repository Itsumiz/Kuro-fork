package dev.kuro.client.command;

import dev.kuro.client.KuroClient;
import dev.kuro.client.config.ConfigManager;
import dev.kuro.client.module.Module;
import dev.kuro.client.module.impl.LogoutSpots;
import dev.kuro.client.module.impl.NewChunks;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chat commands. Anything you type that starts with the prefix (default ".") is handled here and never sent to the server. */
public final class CommandManager {
    private static final Minecraft mc = Minecraft.getInstance();
    public static String prefix = ".";

    private static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("lshift", "left.shift"), Map.entry("rshift", "right.shift"),
            Map.entry("lctrl", "left.control"), Map.entry("rctrl", "right.control"),
            Map.entry("lalt", "left.alt"), Map.entry("ralt", "right.alt"),
            Map.entry("ctrl", "left.control"), Map.entry("shift", "left.shift"), Map.entry("alt", "left.alt"),
            Map.entry("esc", "escape"), Map.entry("return", "enter"), Map.entry("del", "delete"),
            Map.entry("backtick", "grave.accent"), Map.entry("grave", "grave.accent"));

    private CommandManager() {}

    public static void init() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.startsWith(prefix) || message.length() <= prefix.length()) return true;
            try { run(message.substring(prefix.length()).trim()); }
            catch (RuntimeException e) { say("Something went wrong: " + e.getMessage()); }
            return false;                                  // never send it to the server
        });
    }

    // ---------------- output ----------------

    public static void say(String s) {
        if (mc.player == null) return;
        mc.player.displayClientMessage(Component.literal("[Kuro] ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(s).withStyle(ChatFormatting.WHITE)), false);
    }

    // ---------------- dispatch ----------------

    private static void run(String line) {
        if (line.isEmpty()) { help(); return; }
        String[] t = line.split("\\s+");
        String cmd = t[0].toLowerCase(Locale.ROOT);
        String[] a = Arrays.copyOfRange(t, 1, t.length);
        switch (cmd) {
            case "help", "?" -> help();
            case "theme", "themes" -> theme(a);
            case "toggle", "t" -> toggle(a);
            case "bind", "b" -> bind(a);
            case "set" -> set(a);
            case "get", "settings" -> get(a);
            case "friend", "friends", "f" -> friend(a);
            case "config", "cfg" -> config(a);
            case "panic" -> panic();
            case "hudedit", "hud" -> { if (mc.player != null) mc.execute(() -> mc.setScreen(new dev.kuro.client.gui.HudEditScreen(null))); }
            case "coords", "coord" -> {
                var cs = dev.kuro.client.module.impl.CoordShare.get();
                if (cs != null) cs.share(a.length > 0 && a[0].equalsIgnoreCase("chat"));
            }
            case "finds", "found" -> finds();
            case "timer", "stopwatch" -> {
                var sw = dev.kuro.client.module.impl.Stopwatch.get();
                if (sw != null) sw.command(a);
            }
            case "build", "autobuild" -> {
                var ab = dev.kuro.client.module.impl.AutoBuild.get();
                if (ab != null) ab.command(a);
            }
            case "menu", "gui", "clickgui" -> { if (mc.player != null) mc.execute(() -> mc.setScreen(dev.kuro.client.module.ModuleManager.newMenu())); }
            case "menukey", "guikey" -> menuKey(a);
            case "modules", "list" -> modules();
            case "prefix" -> {
                if (a.length == 0 || a[0].isEmpty()) { say("Prefix is \"" + prefix + "\""); return; }
                prefix = a[0].substring(0, 1);
                say("Prefix is now \"" + prefix + "\"");
            }
            case "newchunks" -> {
                if (NewChunks.get() == null) { say("New Chunks isn't loaded."); return; }
                if (a.length > 0 && a[0].equalsIgnoreCase("clear")) NewChunks.get().clear();
                else say("Usage: " + prefix + "newchunks clear");
            }
            case "logouts", "logout" -> {
                if (LogoutSpots.get() == null) { say("Logout Spots isn't loaded."); return; }
                if (a.length > 0 && a[0].equalsIgnoreCase("clear")) LogoutSpots.get().clear();
                else LogoutSpots.get().listInChat();
            }
            default -> say("Unknown command \"" + cmd + "\". Type " + prefix + "help");
        }
    }

    private static void help() {
        say("Commands (prefix \"" + prefix + "\"):");
        say(prefix + "toggle <module>      " + prefix + "bind <module> <key|none>");
        say(prefix + "set <module> <setting> <value>      " + prefix + "get <module>");
        say(prefix + "finds   (opens the list of everything the base finders found)");
        say(prefix + "friend add|remove|list [name]   (no name = player under your crosshair)");
        say(prefix + "config save|load|delete <name>      " + prefix + "config list");
        say(prefix + "modules      " + prefix + "panic (turn everything off)      " + prefix + "prefix <char>");
        say(prefix + "menu (open the click GUI)      " + prefix + "menukey [key|reset|ask] (which key opens it)");
        say(prefix + "hudedit (drag the HUD elements)      " + prefix + "coords [chat] (copy / share your coordinates)");
        say(prefix + "logouts [clear]      " + prefix + "newchunks clear");
        say(prefix + "theme [name|next|prev]   (UI theme for every menu; no name = list them)");
        say(prefix + "timer start|pause|reset|set <1h30m|5m|90s>   (the Stopwatch HUD)");
        say(prefix + "build file|origin|chest|list|info|status|start|stop   (Auto Build with Baritone)");
    }

    /** .theme: list the UI themes, or switch to one by name (or next / prev). */
    private static void theme(String[] a) {
        String[] names = dev.kuro.client.gui.UiThemes.names();
        int cur = dev.kuro.client.module.impl.Hud.themeIndex();
        if (a.length == 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.length; i++) sb.append(i == cur ? "[" + names[i] + "]" : names[i]).append(i < names.length - 1 ? ", " : "");
            say("Themes: " + sb);
            return;
        }
        int n = names.length, idx;
        String q = a[0].toLowerCase(Locale.ROOT);
        if (q.equals("next") || q.equals("n")) idx = (cur + 1) % n;
        else if (q.equals("prev") || q.equals("previous") || q.equals("p")) idx = (cur + n - 1) % n;
        else idx = dev.kuro.client.gui.UiThemes.find(String.join("", a));
        if (idx < 0) { say("No theme called \"" + String.join(" ", a) + "\". " + prefix + "theme lists them"); return; }
        dev.kuro.client.module.impl.Hud.setTheme(idx);
        say("Theme: " + names[idx]);
    }

    // ---------------- module lookup ----------------

    private static String norm(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", ""); }

    private static Module findModule(String name) {
        String n = norm(name);
        if (n.isEmpty()) return null;
        Module partial = null;
        int partials = 0;
        for (Module m : KuroClient.modules.modules) {
            String mn = norm(m.name);
            if (mn.equals(n)) return m;
            if (mn.startsWith(n)) { partial = m; partials++; }
        }
        return partials == 1 ? partial : null;
    }

    private static Setting findSetting(Module m, String name) {
        String n = norm(name);
        if (n.isEmpty()) return null;
        Setting partial = null;
        int partials = 0;
        for (Setting s : m.settings) {
            if (s instanceof Setting.Button) continue;
            String sn = norm(s.name);
            if (sn.equals(n)) return s;
            if (sn.startsWith(n)) { partial = s; partials++; }
        }
        return partials == 1 ? partial : null;
    }

    // ---------------- commands ----------------

    private static void toggle(String[] a) {
        Module m = findModule(String.join(" ", a));
        if (m == null) { say("No single module matches \"" + String.join(" ", a) + "\". Try " + prefix + "modules"); return; }
        m.toggle();
        say(m.name + " is now " + (m.isEnabled() ? "ON" : "OFF"));
    }

    private static void bind(String[] a) {
        if (a.length < 2) { say("Usage: " + prefix + "bind <module> <key|none>"); return; }
        Module m = findModule(String.join(" ", Arrays.copyOfRange(a, 0, a.length - 1)));
        if (m == null) { say("No single module matches that name."); return; }
        String k = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (k.equals("none") || k.equals("off") || k.equals("-") || k.equals("clear")) {
            m.key = -1;
            say(m.name + " unbound");
            return;
        }
        int code = parseKey(k);
        if (code <= 0) { say("I don't know the key \"" + k + "\" (try a letter, f6, rshift, space...)"); return; }
        if (code == dev.kuro.client.util.MenuKey.key()) { say(keyName(code) + " opens the menu. Change that first with " + prefix + "menukey <key>"); return; }
        m.key = code;
        say(m.name + " bound to " + keyName(code));
    }

    private static void set(String[] a) {
        if (a.length < 3) { say("Usage: " + prefix + "set <module> <setting> <value>"); return; }
        for (int k = a.length - 2; k >= 1; k--) {                      // longest module name first
            Module m = findModule(String.join(" ", Arrays.copyOfRange(a, 0, k)));
            if (m == null) continue;
            Setting s = findSetting(m, String.join(" ", Arrays.copyOfRange(a, k, a.length - 1)));
            if (s == null) continue;
            applyValue(m, s, a[a.length - 1]);
            return;
        }
        say("Couldn't find that module/setting. Use " + prefix + "get <module> to see its settings.");
    }

    private static void applyValue(Module m, Setting s, String v) {
        String lv = v.toLowerCase(Locale.ROOT);
        if (s instanceof Setting.Bool b) {
            if (lv.equals("true") || lv.equals("on") || lv.equals("1")) b.value = true;
            else if (lv.equals("false") || lv.equals("off") || lv.equals("0")) b.value = false;
            else if (lv.equals("toggle")) b.value = !b.value;
            else { say("Use on / off / toggle"); return; }
            say(m.name + " / " + s.name + " = " + b.value);
        } else if (s instanceof Setting.Num n) {
            try { n.value = Mth.clamp(Double.parseDouble(v), n.min, n.max); }
            catch (NumberFormatException e) { say("\"" + v + "\" isn't a number"); return; }
            say(m.name + " / " + s.name + " = " + n.value + "  (range " + n.min + " to " + n.max + ")");
        } else if (s instanceof Setting.Hue h) {
            try {
                float f = Float.parseFloat(v);
                if (f > 1f) f /= 360f;                                   // allow degrees
                h.hue = Mth.clamp(f, 0f, 0.999f);
            } catch (NumberFormatException e) { say("Use a number from 0 to 1 (or 0 to 360 degrees)"); return; }
            say(m.name + " / " + s.name + " hue = " + h.hue);
        }
    }

    private static void get(String[] a) {
        Module m = findModule(String.join(" ", a));
        if (m == null) { say("Usage: " + prefix + "get <module>"); return; }
        say(m.name + " (" + (m.isEnabled() ? "ON" : "OFF") + ", key: " + (m.key > 0 ? keyName(m.key) : "none") + ")");
        for (Setting s : m.settings) {
            if (s instanceof Setting.Bool b) say("  " + s.name + " = " + b.value);
            else if (s instanceof Setting.Num n) say("  " + s.name + " = " + n.value);
            else if (s instanceof Setting.Hue h) say("  " + s.name + " hue = " + h.hue);
        }
    }

    private static void friend(String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "list";
        String name = a.length > 1 ? a[1] : null;
        switch (sub) {
            case "add" -> {
                if (name == null) name = crosshairPlayer();
                if (name == null) { say("Look at a player or use " + prefix + "friend add <name>"); return; }
                say(Friends.add(name) ? "Added " + name + " as a friend" : name + " is already a friend");
            }
            case "remove", "del", "delete" -> {
                if (name == null) name = crosshairPlayer();
                if (name == null) { say("Usage: " + prefix + "friend remove <name>"); return; }
                say(Friends.remove(name) ? "Removed " + name : name + " wasn't a friend");
            }
            case "clear" -> { Friends.clear(); say("Friends list cleared"); }
            case "list" -> {
                List<String> l = Friends.list();
                say(l.isEmpty() ? "No friends yet." : "Friends: " + String.join(", ", l));
            }
            default -> say("Usage: " + prefix + "friend add|remove|list [name]");
        }
    }

    private static String crosshairPlayer() {
        if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player p && p != mc.player) {
            return p.getName().getString();
        }
        return null;
    }

    private static void config(String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "list";
        String name = a.length > 1 ? a[1] : null;
        switch (sub) {
            case "save" -> {
                if (name == null) { say("Usage: " + prefix + "config save <name>"); return; }
                say(ConfigManager.save(name) ? "Saved profile \"" + ConfigManager.clean(name) + "\"" : "Couldn't save that profile");
            }
            case "load" -> {
                if (name == null) { say("Usage: " + prefix + "config load <name>"); return; }
                say(ConfigManager.load(name) ? "Loaded profile \"" + ConfigManager.clean(name) + "\"" : "No profile called \"" + name + "\"");
            }
            case "delete", "remove" -> {
                if (name == null) { say("Usage: " + prefix + "config delete <name>"); return; }
                say(ConfigManager.delete(name) ? "Deleted \"" + name + "\"" : "No profile called \"" + name + "\"");
            }
            case "export" -> {
                String s = ConfigManager.exportString(a.length > 1 && a[1].equalsIgnoreCase("friends"));
                mc.keyboardHandler.setClipboard(s);
                say("Config copied to your clipboard (add \"friends\" to include your friends list)");
            }
            case "import" -> {
                String err = ConfigManager.importString(a.length > 1 ? a[1] : mc.keyboardHandler.getClipboard());
                say(err == null ? "Config imported" : err);
            }
            case "list" -> {
                List<String> l = ConfigManager.list();
                say(l.isEmpty() ? "No saved profiles. Settings also save automatically." : "Profiles: " + String.join(", ", l));
            }
            default -> say("Usage: " + prefix + "config save|load|delete|list|export|import [name]");
        }
    }

    /** .menukey: no argument = ask for a key on screen; .menukey <key> sets it; .menukey reset = Right Shift again. */
    private static void menuKey(String[] a) {
        if (a.length == 0 || a[0].isEmpty()) {
            say("Menu key: " + dev.kuro.client.util.MenuKey.name(dev.kuro.client.util.MenuKey.key())
                    + "  (" + prefix + "menukey <key> to change it, " + prefix + "menukey reset for Right Shift, " + prefix + "menukey ask to pick on screen)");
            return;
        }
        String k = a[0].toLowerCase(Locale.ROOT);
        if (k.equals("ask") || k.equals("pick")) {
            if (mc.player != null) mc.execute(() -> mc.setScreen(new dev.kuro.client.gui.MenuKeyScreen(null, false)));
            return;
        }
        if (k.equals("reset") || k.equals("default")) {
            dev.kuro.client.util.MenuKey.reset();
            say("Menu key is now " + dev.kuro.client.util.MenuKey.name(dev.kuro.client.util.MenuKey.DEFAULT));
            return;
        }
        int code = parseKey(k);
        if (code <= 0) { say("I don't know the key \"" + k + "\" (try a letter, f6, rshift, space...)"); return; }
        if (code != dev.kuro.client.util.MenuKey.DEFAULT) {
            String problem = dev.kuro.client.util.MenuKey.problem(code);
            if (problem != null) { say(problem); return; }
        }
        dev.kuro.client.util.MenuKey.set(code);
        say("Menu key is now " + dev.kuro.client.util.MenuKey.name(code));
    }

    private static void finds() {
        if (mc.player != null) mc.execute(() -> mc.setScreen(new dev.kuro.client.gui.FoundListScreen(null)));
    }

    private static void panic() {
        int n = 0;
        dev.kuro.client.module.impl.Notifications.quiet(true);
        try { for (Module m : KuroClient.modules.modules) if (m.isEnabled()) { m.setEnabled(false); n++; } }
        finally { dev.kuro.client.module.impl.Notifications.quiet(false); }
        dev.kuro.client.module.impl.Notifications.info("Panic", n + " module" + (n == 1 ? "" : "s") + " disabled", false);
        say("Turned off " + n + " module" + (n == 1 ? "" : "s"));
    }

    private static void modules() {
        List<String> on = new ArrayList<>(), off = new ArrayList<>();
        for (Module m : KuroClient.modules.modules) (m.isEnabled() ? on : off).add(m.name);
        say("ON: " + (on.isEmpty() ? "none" : String.join(", ", on)));
        say("OFF: " + String.join(", ", off));
    }

    // ---------------- keys ----------------

    private static int parseKey(String k) {
        k = KEY_ALIASES.getOrDefault(k, k);
        try {
            return InputConstants.getKey("key.keyboard." + k).getValue();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static String keyName(int key) {
        String n = GLFW.glfwGetKeyName(key, 0);
        if (n != null) return n.toUpperCase(Locale.ROOT);
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) return "RSHIFT";
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT) return "LSHIFT";
        if (key == GLFW.GLFW_KEY_SPACE) return "SPACE";
        return "K" + key;
    }
}
