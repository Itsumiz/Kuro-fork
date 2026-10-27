package dev.kuro.client.module.impl;

import dev.kuro.client.mixin.InteractionManagerInvoker;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;

/** Removes the delay between breaking two blocks. */
public class NoBreakDelay extends Module {
    public NoBreakDelay() { super("No Break Delay", Category.PLAYER); }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.gameMode == null) return;
        ((InteractionManagerInvoker) mc.gameMode).kuro$setDestroyDelay(0);
    }
}
