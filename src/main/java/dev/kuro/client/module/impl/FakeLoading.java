package dev.kuro.client.module.impl;

import dev.kuro.client.gui.FakeLoadingScreen;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * A fake loading screen with a sound. While this module is on, it plays once the next time the game starts (over the
 * title screen). "Test" plays it right now. Esc skips it.
 */
public class FakeLoading extends Module {
    private static FakeLoading instance;
    private static boolean shownThisLaunch;

    private final Setting.Num duration = add(new Setting.Num("Duration (s)", 27, 1, 60, 0.5));
    private final Setting.Bool sound = add(new Setting.Bool("Sound", true));

    public FakeLoading() {
        super("Fake Loading", Category.MISC);
        instance = this;
        add(new Setting.Button("Test", () -> show(Minecraft.getInstance())));
    }

    private void show(Minecraft mc) {
        if (mc == null) return;
        mc.execute(() -> mc.setScreen(new FakeLoadingScreen(mc.screen, duration.value, sound.value)));
    }

    /** Called every client tick: on the first title screen of a launch, plays the fake load if the module is on. */
    public static void tick(Minecraft mc) {
        if (shownThisLaunch || instance == null || !(mc.screen instanceof TitleScreen)) return;
        shownThisLaunch = true;
        if (instance.isEnabled()) instance.show(mc);
    }
}
