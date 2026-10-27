package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.gui.MenuKeyScreen;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.MenuKey;

/**
 * Menu: which key opens the click GUI (Right Shift by default). "Set menu key..." asks for a key; the same thing is
 * available as .menukey. Switching the module itself on does the same as the first button and then switches off again.
 */
public class MenuModule extends Module {
    public MenuModule() {
        super("Menu", Category.MISC);
        add(new Setting.Button("Set menu key...", MenuModule::askForKey));
        add(new Setting.Button("Reset to Right Shift", () -> {
            MenuKey.reset();
            CommandManager.say("Menu key is now " + MenuKey.name(MenuKey.DEFAULT));
        }));
        add(new Setting.Button("Show current key", () -> CommandManager.say("Menu key: " + MenuKey.name(MenuKey.key()))));
    }

    private static void askForKey() {
        mc.setScreen(new MenuKeyScreen(mc.screen, false));
    }

    @Override
    protected void onEnable() {
        askForKey();
        setEnabled(false);                 // a button, not a switch
    }
}
