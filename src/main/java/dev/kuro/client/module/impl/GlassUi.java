package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;

/**
 * Menu style switch: the Glass menu (gui/GlassGuiScreen). A floating frosted-glass panel with an icon rail, module
 * cards and a settings pane, drawn entirely with shaders (see util/ShaderUi and tools/gen_shaders.py) and animated
 * with springs. It is ON by default and wins over Blob UI and Sidebar UI. Switching it off (the "Classic UI" button in
 * this module's settings, or this module's switch) brings back whichever older menu is active: Blob UI, Sidebar UI, or the
 * draggable windows.
 * To come back to the glass menu, switch this module on again from any of the old menus (Render category).
 * Its colours and background come from the shared UI theme (HUD > UI theme, gui/UiThemes), like every other menu.
 */
public class GlassUi extends Module {
    private static GlassUi instance;
    private static boolean classicRequested;

    /** Fades the glass menu out and opens the older menu (Render > Glass UI > Classic UI). */
    private final Setting.Button classic = add(new Setting.Button("Classic UI", GlassUi::requestClassic));
    private final Setting.Num opacity = add(new Setting.Num("Opacity %", 100, 20, 100, 5));
    private final Setting.Bool descriptions = add(new Setting.Bool("Descriptions", true));
    private final Setting.Bool cursorLight = add(new Setting.Bool("Cursor light", true));
    private final Setting.Num speed = add(new Setting.Num("Animation speed", 1.0, 0.5, 2.0, 0.1));

    public GlassUi() {
        super("Glass UI", Category.RENDER);
        instance = this;
    }

    public static boolean active() { return instance != null && instance.isEnabled(); }
    /** UI see-through control: 1 = the normal look, lower = more of the blurred world shows through the panels. */
    public static float opacity() { return instance == null ? 1f : (float) (instance.opacity.value / 100.0); }
    /** The flag theme (set in HUD > UI theme) draws the flag as the panel and needs a white plate behind the settings pane. */
    public static boolean israel() { return dev.kuro.client.gui.UiThemes.current().decor == dev.kuro.client.gui.UiThemes.Decor.FLAG; }
    public static boolean descriptions() { return instance == null || instance.descriptions.value; }
    public static boolean cursorLight() { return instance == null || instance.cursorLight.value; }
    public static float speed() { return instance == null ? 1f : (float) instance.speed.value; }

    /** The "Classic UI" button was pressed: the open glass menu picks this up, fades out, then calls leave(). */
    public static void requestClassic() { classicRequested = active(); }   // only meaningful while the glass menu is the active one
    public static boolean consumeClassicRequest() { boolean r = classicRequested; classicRequested = false; return r; }

    /** Leave the glass menu (ModuleManager swaps to the matching older menu on the next tick). */
    public static void leave() {
        if (instance != null) instance.setEnabled(false);
    }
}
