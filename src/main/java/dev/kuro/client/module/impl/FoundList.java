package dev.kuro.client.module.impl;

import dev.kuro.client.gui.FoundListScreen;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;

/** Opens the Found List screen (also bindable to a key, and available as .finds). Switches itself straight back off. */
public class FoundList extends Module {
    public FoundList() { super("Found List", Category.BASE); }

    @Override
    protected void onEnable() {
        if (mc.player != null) mc.setScreen(new FoundListScreen(null));
        setEnabled(false);
    }
}
