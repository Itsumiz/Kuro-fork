package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Walk up blocks taller than a slab by raising your step height (vanilla 0.6). Servers with movement checks will flag this. */
public class Step extends Module {
    private static final double VANILLA = 0.6;
    private final Setting.Num height = add(new Setting.Num("Height", 1.0, 1.0, 3.0, 0.1));
    private final Setting.Bool disableOnSneak = add(new Setting.Bool("Disable on sneak", false));

    public Step() { super("Step", Category.MOVEMENT); }

    @Override protected void onDisable() { set(VANILLA); }

    @Override
    public void onPreTick() {
        if (mc.player == null) return;
        set(disableOnSneak.value && mc.player.isShiftKeyDown() ? VANILLA : height.value);
    }

    private void set(double value) {
        if (mc.player == null) return;
        AttributeInstance a = mc.player.getAttribute(Attributes.STEP_HEIGHT);
        if (a != null && a.getBaseValue() != value) a.setBaseValue(value);
    }
}
