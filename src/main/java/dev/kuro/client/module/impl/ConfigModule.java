package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.config.ConfigManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * Config slots: up to 6 saved configs (ConfigManager.SLOTS). The Sidebar menu shows them as cards on its Configs page
 * (name, save / load / delete per slot); the other menus use the Slot selector + buttons below.
 */
public class ConfigModule extends Module {
    private final Setting.Num slot = add(new Setting.Num("Slot", 1, 1, ConfigManager.SLOTS, 1));
    private final Setting.Button saveBtn, loadBtn, deleteBtn;
    private final Setting.Bool withFriends = add(new Setting.Bool("Export friends", false));

    /** One text box per slot for renaming (used by the Sidebar's slot cards; not part of the settings list). */
    private final Setting.Text[] renameBoxes = new Setting.Text[ConfigManager.SLOTS];
    /** Name typed for a slot that is still empty: used by its first Save. */
    private final String[] pendingNames = new String[ConfigManager.SLOTS];

    public ConfigModule() {
        super("Config", Category.MISC);
        saveBtn = new Setting.Button("Save slot", () -> CommandManager.say(
                ConfigManager.saveSlot(slot.intValue() - 1, "") ? "Saved to slot " + slot.intValue() : "Couldn't save slot " + slot.intValue()));
        loadBtn = new Setting.Button("Load slot", () -> CommandManager.say(
                ConfigManager.loadSlot(slot.intValue() - 1) ? "Loaded slot " + slot.intValue() : "Nothing saved in slot " + slot.intValue()));
        deleteBtn = new Setting.Button("Delete slot", () -> CommandManager.say(
                ConfigManager.deleteSlot(slot.intValue() - 1) ? "Deleted slot " + slot.intValue() : "Nothing saved in slot " + slot.intValue()));
        settings.add(1, saveBtn);
        settings.add(2, loadBtn);
        settings.add(3, deleteBtn);
        add(new Setting.Button("Export to clipboard", () -> {
            String s = ConfigManager.exportString(withFriends.value);
            if (s.isEmpty()) { CommandManager.say("Export failed"); return; }
            mc.keyboardHandler.setClipboard(s);
            CommandManager.say("Config copied to your clipboard (" + s.length() + " chars). Paste it anywhere to share.");
        }));
        add(new Setting.Button("Import from clipboard", () -> {
            String err = ConfigManager.importString(mc.keyboardHandler.getClipboard());
            CommandManager.say(err == null ? "Config imported" : err);
        }));
        add(new Setting.Button("List profiles", () -> {
            var l = ConfigManager.list();
            CommandManager.say(l.isEmpty() ? "No saved profiles." : "Profiles: " + String.join(", ", l));
        }));
        for (int i = 0; i < renameBoxes.length; i++) {
            final int idx = i;
            pendingNames[i] = "";
            renameBoxes[i] = new Setting.Text("Name", text -> {
                if (ConfigManager.slot(idx).used()) ConfigManager.renameSlot(idx, text);
                else pendingNames[idx] = text.length() > 24 ? text.substring(0, 24) : text;
            });
        }
    }

    public Setting.Text renameBox(int slotIndex) { return renameBoxes[slotIndex]; }
    public String pendingName(int slotIndex) { return pendingNames[slotIndex]; }
    /** The name typed for an empty slot (and forgets it), or "" when none. */
    public String takePendingName(int slotIndex) { String n = pendingNames[slotIndex]; pendingNames[slotIndex] = ""; return n; }

    /** Everything except the slot selector and its three buttons: what the Sidebar still lists under its own slot cards. */
    public List<Setting> extraSettings() {
        List<Setting> out = new ArrayList<>(settings);
        out.remove(slot);
        out.remove(saveBtn);
        out.remove(loadBtn);
        out.remove(deleteBtn);
        return out;
    }
}
