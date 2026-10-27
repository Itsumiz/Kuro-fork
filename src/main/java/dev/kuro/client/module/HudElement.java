package dev.kuro.client.module;

import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Projector;
import net.minecraft.util.Mth;

/**
 * A module that draws one movable plate on the HUD. The HUD layout editor (.hudedit, or HUD > "Edit HUD layout")
 * lets you drag every enabled HudElement; the position lives in the module's X / Y settings so it is saved with the config.
 */
public interface HudElement {
    Setting.Num hudX();
    Setting.Num hudY();
    /** Size of the plate as last drawn (a sensible default before the first frame). */
    int hudW();
    int hudH();
    String hudName();

    /** X / Y settings clamped so a plate of this size stays fully on screen (also after a GUI-scale or window change). */
    static int clampX(Setting.Num n, int w) { return Mth.clamp(n.intValue(), 0, Math.max(0, Projector.width() - w)); }
    static int clampY(Setting.Num n, int h) { return Mth.clamp(n.intValue(), 0, Math.max(0, Projector.height() - h)); }

    default int drawX() { return clampX(hudX(), hudW()); }
    default int drawY() { return clampY(hudY(), hudH()); }

    /** Right click in the layout editor: put the plate back where it sits by default (only panels with an automatic spot do anything). */
    default void resetPos() {}
}
