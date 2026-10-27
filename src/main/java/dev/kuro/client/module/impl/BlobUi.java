package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;

/**
 * Menu style switch: the "blob" menu. A hub of big glowing organic blobs (one per category) around a centre badge;
 * click a blob to open that category's modules in a glass panel. Everything is drawn by shaders (see util/ShaderUi,
 * ui_blobx*.fsh). It wins over the Sidebar UI when both are on. Flipping it while the menu is open swaps the screen
 * straight away (ModuleManager#onTick). Colours follow HUD -> Color / Saturation.
 */
public class BlobUi extends Module {
    private static BlobUi instance;
    private final Setting.Bool counts = add(new Setting.Bool("Show counts", true));
    private final Setting.Bool snow = add(new Setting.Bool("Falling snow", true));

    public BlobUi() {
        super("Blob UI", Category.RENDER);
        instance = this;
    }

    public static boolean active() { return instance != null && instance.isEnabled(); }
    public static boolean counts() { return instance == null || instance.counts.value; }
    public static boolean snow() { return instance == null || instance.snow.value; }
}
