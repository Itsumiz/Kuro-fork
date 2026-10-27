package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import net.minecraft.client.KeyMapping;

/** Holds the forward key for you. Pauses while a screen is open or in Freecam. */
public class AutoWalk extends Module {
    private boolean pressedByUs;

    public AutoWalk() { super("Auto Walk", Category.MOVEMENT); }

    @Override protected void onDisable() { release(); }

    private void release() {
        if (pressedByUs) { pressedByUs = false; KeyMapping.setAll(); } // re-sync with the real key
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.screen != null || Freecam.active()) { release(); return; }
        mc.options.keyUp.setDown(true);
        pressedByUs = true;
    }
}
