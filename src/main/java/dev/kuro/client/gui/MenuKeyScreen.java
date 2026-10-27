package dev.kuro.client.gui;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.MenuKey;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * "Press the key that opens the menu". Shown once on the first start (over the title screen), and again from the Menu
 * module or .menukey. Press any key to pick it; the two buttons keep / restore Right Shift or cancel.
 */
public class MenuKeyScreen extends Screen {
    private final Screen parent;
    private final boolean first;
    private final long openedAt = System.currentTimeMillis();
    private String problem;
    private int bx1, by, bx2, bx3, bw = 120, bh = 18;

    public MenuKeyScreen(Screen parent, boolean first) {
        super(Component.literal("Menu key"));
        this.parent = parent;
        this.first = first;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xB0000000);
    }

    private void line(GuiGraphics g, String s, int y, int color) {
        SdfText.ui(g, s, (width - SdfText.w(s)) / 2f, y, color, true);
    }

    private void button(GuiGraphics g, int x, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + bw && my >= by && my < by + bh;
        Look.panel(g, x, by, x + bw, by + bh, hover ? Look.ACCENT : Look.MUTED);
        SdfText.ui(g, label, x + (bw - SdfText.w(label)) / 2f, by + 5, Look.TEXT, false);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        int cy = height / 2 - 40;
        line(g, first ? "Welcome. Which key should open the menu?" : "Menu key", cy, Look.TEXT);
        line(g, "Press the key you want to use", cy + 16, Look.MUTED);
        line(g, "Now: " + MenuKey.name(MenuKey.key()), cy + 32, Look.ACCENT);
        if (problem != null) line(g, problem, cy + 48, Look.BAD);

        by = cy + 66;
        bx1 = width / 2 - bw - 4;
        bx2 = width / 2 + 4;
        button(g, bx1, first ? "Keep Right Shift" : "Reset to Right Shift", mx, my);
        button(g, bx2, first ? "Skip" : "Cancel", mx, my);
        line(g, "You can change this later: Menu module, or .menukey", by + 32, Look.MUTED);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() != 0) return true;
        double x = click.x(), y = click.y();
        if (y >= by && y < by + bh) {
            if (x >= bx1 && x < bx1 + bw) { choose(MenuKey.DEFAULT); return true; }
            if (x >= bx2 && x < bx2 + bw) { cancel(); return true; }
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (System.currentTimeMillis() - openedAt < 400) return true;   // the key that opened this screen (Enter from chat...) must not pick itself
        int key = input.input();
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { cancel(); return true; }
        if (key != MenuKey.DEFAULT) {
            problem = MenuKey.problem(key);
            if (problem != null) return true;
        }
        choose(key);
        return true;
    }

    private void choose(int key) {
        MenuKey.set(key);
        CommandManager.say("Menu key is now " + MenuKey.name(key));
        onClose();
    }

    private void cancel() {
        MenuKey.markAsked();
        onClose();
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
