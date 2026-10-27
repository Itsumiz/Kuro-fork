package dev.kuro.client.gui;

import dev.kuro.client.KuroClient;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Drag every enabled HUD element (Clock, Armor HUD, Potion HUD, Keystrokes, Spotify, the HUD's own panels, notifications,
 * totem pops...) where you want it. The elements are drawn by the normal HUD underneath; this screen only draws their
 * outlines and moves their X / Y settings.
 * Edges snap to the screen edges / centre and to the other elements. Arrow keys nudge (Shift = 5px).
 * Right click an element = back to its default spot. Esc finishes.
 */
public class HudEditScreen extends Screen {
    private static final double SNAP = 4.0;
    private static boolean editing;

    /** True while this screen is open: empty elements (no armour, no potions) draw a placeholder so they can be found and moved. */
    public static boolean editing() { return editing; }

    private final Screen parent;
    private final List<HudElement> els = new ArrayList<>();
    private final List<int[]> guides = new ArrayList<>();   // {0 = vertical line / 1 = horizontal line, position}
    private HudElement sel;
    private double grabX, grabY;

    public HudEditScreen(Screen parent) {
        super(Component.literal("HUD layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        els.clear();
        for (Module m : KuroClient.modules.modules)
            if (m.isEnabled()) els.addAll(m.hudElements());
        if (sel != null && !els.contains(sel)) sel = null;
        editing = true;
    }

    @Override public void removed() { editing = false; }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x50000000);   // light dim only: the real HUD stays visible underneath
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);

        for (int[] gd : guides) {
            if (gd[0] == 0) g.fill(gd[1], 0, gd[1] + 1, height, Look.withAlpha(Look.ACCENT, 0x90));
            else g.fill(0, gd[1], width, gd[1] + 1, Look.withAlpha(Look.ACCENT, 0x90));
        }

        for (HudElement e : els) {
            int x = e.drawX(), y = e.drawY(), w = e.hudW(), h = e.hudH();
            boolean hover = hit(e, mx, my);
            int c = e == sel ? Look.ACCENT : hover ? 0xFFFFFFFF : 0x90FFFFFF;
            Ui.outline(g, x - 2, y - 2, x + w + 2, y + h + 2, c);
            String label = e.hudName();
            int ly = y >= 13 ? y - 12 : y + h + 4;
            SdfText.ui(g, label, x, ly, c, true);
        }

        String help = els.isEmpty()
                ? "No HUD elements are on. Enable the HUD, Clock, Armor HUD, Potion HUD, Keystrokes or Spotify first.  Esc to close."
                : "Drag to move  -  arrow keys nudge (Shift = 5)  -  right click = reset  -  Esc to finish";
        SdfText.ui(g, help, (width - SdfText.w(help)) / 2f, 6, Look.TEXT, true);
    }

    private static boolean hit(HudElement e, double mx, double my) {
        int x = e.drawX(), y = e.drawY();
        return mx >= x - 2 && mx <= x + e.hudW() + 2 && my >= y - 2 && my <= y + e.hudH() + 2;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == 1) {                          // right click: that plate goes back to its default spot
            for (int i = els.size() - 1; i >= 0; i--) {
                if (hit(els.get(i), click.x(), click.y())) { els.get(i).resetPos(); return true; }
            }
            return super.mouseClicked(click, doubled);
        }
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        sel = null;
        for (int i = els.size() - 1; i >= 0; i--) {   // topmost (last drawn) first
            HudElement e = els.get(i);
            if (hit(e, click.x(), click.y())) {
                sel = e;
                grabX = click.x() - e.drawX();
                grabY = click.y() - e.drawY();
                break;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (sel == null || click.button() != 0) return super.mouseDragged(click, offsetX, offsetY);
        guides.clear();
        double nx = snap(click.x() - grabX, sel.hudW(), width, true);
        double ny = snap(click.y() - grabY, sel.hudH(), height, false);
        sel.hudX().value = Mth.clamp(Math.round(nx), 0, Math.max(0, width - sel.hudW()));
        sel.hudY().value = Mth.clamp(Math.round(ny), 0, Math.max(0, height - sel.hudH()));
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        guides.clear();
        return super.mouseReleased(click);
    }

    /** Pulls the element's left / centre / right (or top / middle / bottom) edge onto a nearby target line. */
    private double snap(double pos, int size, int total, boolean xAxis) {
        List<Integer> targets = new ArrayList<>(List.of(0, total / 2, total));
        for (HudElement o : els) {
            if (o == sel) continue;
            int p = xAxis ? o.drawX() : o.drawY(), s = xAxis ? o.hudW() : o.hudH();
            targets.add(p); targets.add(p + s / 2); targets.add(p + s);
        }
        double best = pos, bestD = SNAP + 0.001;
        int guide = -1;
        for (int t : targets) {
            for (int off : new int[]{0, size / 2, size}) {
                double d = t - (pos + off);
                if (Math.abs(d) < bestD) { bestD = Math.abs(d); best = pos + d; guide = t; }
            }
        }
        if (guide >= 0) guides.add(new int[]{xAxis ? 0 : 1, guide});
        return best;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.input();
        if (sel != null && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            long win = minecraft.getWindow().handle();
            boolean shift = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
            int step = shift ? 5 : 1;
            int dx = key == GLFW.GLFW_KEY_LEFT ? -step : key == GLFW.GLFW_KEY_RIGHT ? step : 0;
            int dy = key == GLFW.GLFW_KEY_UP ? -step : key == GLFW.GLFW_KEY_DOWN ? step : 0;
            sel.hudX().value = Mth.clamp(sel.drawX() + dx, 0, Math.max(0, width - sel.hudW()));
            sel.hudY().value = Mth.clamp(sel.drawY() + dy, 0, Math.max(0, height - sel.hudH()));
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
