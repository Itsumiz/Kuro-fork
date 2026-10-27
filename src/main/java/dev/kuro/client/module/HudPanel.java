package dev.kuro.client.module;

import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Projector;
import net.minecraft.util.Mth;

/**
 * One draggable plate that belongs to a module which draws several of them (the HUD's title pill, keybinds list...),
 * or whose natural spot depends on the screen size (notifications, the module list on the right edge).
 *
 * Until it has been dragged it sits at the automatic spot the module hands to place() every frame. Once the player
 * drags it, the X / Y are stored (hidden settings, saved with the config) and win over the automatic spot.
 * Right click in the layout editor goes back to automatic.
 */
public final class HudPanel implements HudElement {
    private final String label;
    private final Setting.Num x, y;          // below 0 = automatic position
    private int w = 60, h = 14, lastX, lastY;

    public HudPanel(Module owner, String label) {
        this.label = label;
        this.x = owner.addSaved(new Setting.Num(label + " X", -1, -1, 4000, 1));
        this.y = owner.addSaved(new Setting.Num(label + " Y", -1, -1, 4000, 1));
    }

    public boolean moved() { return x.value >= 0 && y.value >= 0; }

    /**
     * Call once per frame before drawing, with the plate's size and the spot it takes when nobody moved it.
     * Afterwards x() / y() say where to draw (always fully on screen).
     */
    public HudPanel place(int autoX, int autoY, int width, int height) {
        w = width; h = height;
        int maxX = Math.max(0, Projector.width() - w), maxY = Math.max(0, Projector.height() - h);
        lastX = Mth.clamp(moved() ? x.intValue() : autoX, 0, maxX);
        lastY = Mth.clamp(moved() ? y.intValue() : autoY, 0, maxY);
        return this;
    }

    public int x() { return lastX; }
    public int y() { return lastY; }

    @Override public Setting.Num hudX() { return x; }
    @Override public Setting.Num hudY() { return y; }
    @Override public int hudW() { return w; }
    @Override public int hudH() { return h; }
    @Override public String hudName() { return label; }
    @Override public int drawX() { return lastX; }
    @Override public int drawY() { return lastY; }
    @Override public void resetPos() { x.value = -1; y.value = -1; }
}
