package dev.kuro.client.util;

import dev.kuro.client.KuroClient;
import dev.kuro.client.gui.MenuKeyScreen;
import dev.kuro.client.module.Module;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The key that opens (and closes) the click GUI. Right Shift until you pick another one: on the first start the client
 * asks (gui/MenuKeyScreen), and you can change it any time with the Menu module, or with .menukey.
 *
 * It is kept in its own file (config/kuro-menukey.txt), not in the config slots, so loading or importing a config
 * never changes the key you open the menu with.
 */
public final class MenuKey {
    public static final int DEFAULT = GLFW.GLFW_KEY_RIGHT_SHIFT;

    private static int key = DEFAULT;
    private static boolean asked;        // the first-start question has been answered (or skipped)
    private static boolean loaded;

    private MenuKey() {}

    public static int key() { load(); return key; }
    public static boolean asked() { load(); return asked; }

    public static void set(int k) { load(); key = k; asked = true; save(); }
    public static void reset() { set(DEFAULT); }
    /** The first-start question was closed without picking: keep what is there, don't ask again. */
    public static void markAsked() { load(); if (!asked) { asked = true; save(); } }

    /** Called every client tick: on the first start, shows the question over the title screen. */
    public static void tick(Minecraft mc) {
        if (asked() || !(mc.screen instanceof TitleScreen)) return;
        mc.setScreen(new MenuKeyScreen(mc.screen, true));
    }

    /** Why this key can't open the menu, or null when it can. */
    public static String problem(int k) {
        if (k <= 0 || k == GLFW.GLFW_KEY_ESCAPE) return "That key can't be used";
        List<Module> mods = KuroClient.modules == null ? List.of() : KuroClient.modules.modules;
        for (Module m : mods) if (m.key == k) return name(k) + " is already the key for " + m.name;
        return null;
    }

    /** "Right Shift", "K", "F6"... */
    public static String name(int k) {
        switch (k) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT: return "Right Shift";
            case GLFW.GLFW_KEY_LEFT_SHIFT: return "Left Shift";
            case GLFW.GLFW_KEY_RIGHT_CONTROL: return "Right Ctrl";
            case GLFW.GLFW_KEY_LEFT_CONTROL: return "Left Ctrl";
            case GLFW.GLFW_KEY_RIGHT_ALT: return "Right Alt";
            case GLFW.GLFW_KEY_LEFT_ALT: return "Left Alt";
            case GLFW.GLFW_KEY_SPACE: return "Space";
            case GLFW.GLFW_KEY_TAB: return "Tab";
            case GLFW.GLFW_KEY_ENTER: return "Enter";
            case GLFW.GLFW_KEY_BACKSPACE: return "Backspace";
            case GLFW.GLFW_KEY_CAPS_LOCK: return "Caps Lock";
            case GLFW.GLFW_KEY_INSERT: return "Insert";
            case GLFW.GLFW_KEY_DELETE: return "Delete";
            case GLFW.GLFW_KEY_HOME: return "Home";
            case GLFW.GLFW_KEY_END: return "End";
            case GLFW.GLFW_KEY_PAGE_UP: return "Page Up";
            case GLFW.GLFW_KEY_PAGE_DOWN: return "Page Down";
            case GLFW.GLFW_KEY_UP: return "Up";
            case GLFW.GLFW_KEY_DOWN: return "Down";
            case GLFW.GLFW_KEY_LEFT: return "Left";
            case GLFW.GLFW_KEY_RIGHT: return "Right";
            default: break;
        }
        if (k >= GLFW.GLFW_KEY_F1 && k <= GLFW.GLFW_KEY_F25) return "F" + (k - GLFW.GLFW_KEY_F1 + 1);
        String n = GLFW.glfwGetKeyName(k, 0);
        return n != null ? n.toUpperCase(Locale.ROOT) : "Key " + k;
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("kuro-menukey.txt"); }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            List<String> lines = Files.readAllLines(p);
            if (lines.size() >= 1) key = Integer.parseInt(lines.get(0).trim());
            asked = lines.size() >= 2 && lines.get(1).trim().equals("asked");
            if (key <= 0) key = DEFAULT;
        } catch (Exception ignored) {}
    }

    private static void save() {
        try { Files.writeString(file(), key + "\n" + (asked ? "asked" : "")); } catch (Exception ignored) {}
    }
}
