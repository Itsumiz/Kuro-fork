package dev.kuro.client.gui;

import net.minecraft.client.Minecraft;

/** Implemented by every module menu (classic windows, the sidebar menu, blob and glass menus) so the key handling can treat them alike. */
public interface ModuleGui {
    /** True while the menu is waiting for a key to bind or a text box has focus (so Right Shift must not close it). */
    boolean isBinding();

    /** Right Shift while the menu is open. Menus with a closing animation override this and fade out first. */
    default void requestClose() { Minecraft.getInstance().setScreen(null); }
}
