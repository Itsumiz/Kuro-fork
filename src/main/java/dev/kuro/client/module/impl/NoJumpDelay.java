package dev.kuro.client.module.impl;

import dev.kuro.client.mixin.LivingEntityAccessor;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;

/** Removes the jump cooldown, so holding space bunny-hops every tick you touch the ground. */
public class NoJumpDelay extends Module {
    public NoJumpDelay() { super("No Jump Delay", Category.MOVEMENT); }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) return;
        ((LivingEntityAccessor) mc.player).kuro$setNoJumpDelay(0);
    }
}
