package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;

/**
 * Menu style switch. Off = the classic draggable windows, On = the sidebar menu (category list on the left,
 * module rows with a description and a switch on the right). Flipping it while the menu is open swaps the
 * screen straight away (see ModuleManager#onTick). Colours follow the HUD module's Color setting.
 */
public class SidebarUi extends Module {
    private static SidebarUi instance;
    private final Setting.Bool descriptions = add(new Setting.Bool("Descriptions", true));
    private final Setting.Bool counts = add(new Setting.Bool("Category counts", true));

    public SidebarUi() {
        super("Sidebar UI", Category.RENDER);
        instance = this;
    }

    public static boolean active() { return instance != null && instance.isEnabled(); }
    public static boolean descriptions() { return instance == null || instance.descriptions.value; }
    public static boolean counts() { return instance == null || instance.counts.value; }
}
