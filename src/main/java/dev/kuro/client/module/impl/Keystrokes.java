package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.Ui;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;

/** W A S D (plus optional mouse buttons with CPS, and space) that light up while pressed, as a movable HUD element. */
public class Keystrokes extends Module implements HudElement {
    private static final int K = 22, GAP = 2;
    private static final int W = K * 3 + GAP * 2;

    private final Setting.Num x = add(new Setting.Num("X", 6, 0, 4000, 1));
    private final Setting.Num y = add(new Setting.Num("Y", 180, 0, 4000, 1));
    private final Setting.Bool mouse = add(new Setting.Bool("Mouse buttons", true));
    private final Setting.Bool cps = add(new Setting.Bool("CPS", true));
    private final Setting.Bool space = add(new Setting.Bool("Space bar", true));

    private final float[] anim = new float[7];          // W A S D LMB RMB SPACE: 0 = up, 1 = down
    private final ArrayDeque<Long> lmb = new ArrayDeque<>(), rmb = new ArrayDeque<>();
    private boolean lmbWas, rmbWas;
    private long lastNs;
    private int lastH = 48;

    public Keystrokes() { super("Keystrokes", Category.RENDER); }

    @Override public Setting.Num hudX() { return x; }
    @Override public Setting.Num hudY() { return y; }
    @Override public int hudW() { return W; }
    @Override public int hudH() { return lastH; }
    @Override public String hudName() { return name; }

    @Override
    protected void onEnable() { lastNs = 0; lmb.clear(); rmb.clear(); }

    /** Rising edge of a physical mouse button -> one click for the CPS counter (ignored while a menu is open). */
    private boolean poll(long win, int button, boolean was, ArrayDeque<Long> clicks, long now) {
        boolean down = GLFW.glfwGetMouseButton(win, button) == GLFW.GLFW_PRESS;
        if (down && !was && mc.screen == null) clicks.addLast(now);
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000) clicks.pollFirst();
        return down;
    }

    private static boolean down(KeyMapping k) { return k != null && k.isDown(); }

    @Override
    public void onHud(GuiGraphics g) {
        if (mc.player == null || mc.getWindow() == null) return;
        long win = mc.getWindow().handle(), now = System.currentTimeMillis(), ns = System.nanoTime();
        float dt = lastNs == 0 ? 1f : Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;

        lmbWas = poll(win, GLFW.GLFW_MOUSE_BUTTON_LEFT, lmbWas, lmb, now);
        rmbWas = poll(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT, rmbWas, rmb, now);
        boolean[] target = {
                down(mc.options.keyUp), down(mc.options.keyLeft), down(mc.options.keyDown), down(mc.options.keyRight),
                lmbWas, rmbWas, down(mc.options.keyJump) };
        for (int i = 0; i < anim.length; i++)
            anim[i] += ((target[i] ? 1f : 0f) - anim[i]) * Math.min(1f, dt * 20f);   // quick fade, frame-rate independent

        int h = K * 2 + GAP + (mouse.value ? GAP + K : 0) + (space.value ? GAP + 14 : 0);
        lastH = h;
        int x1 = HudElement.clampX(x, W), y1 = HudElement.clampY(y, h);

        key(g, x1 + K + GAP, y1, K, K, "W", null, anim[0]);
        key(g, x1, y1 + K + GAP, K, K, "A", null, anim[1]);
        key(g, x1 + K + GAP, y1 + K + GAP, K, K, "S", null, anim[2]);
        key(g, x1 + (K + GAP) * 2, y1 + K + GAP, K, K, "D", null, anim[3]);
        int y2 = y1 + (K + GAP) * 2;
        if (mouse.value) {
            int bw = (W - GAP) / 2;
            key(g, x1, y2, bw, K, "LMB", cps.value ? String.valueOf(lmb.size()) : null, anim[4]);
            key(g, x1 + W - bw, y2, bw, K, "RMB", cps.value ? String.valueOf(rmb.size()) : null, anim[5]);
            y2 += K + GAP;
        }
        if (space.value) {
            key(g, x1, y2, W, 14, "", null, anim[6]);
            int cx = x1 + W / 2;
            g.fill(cx - 8, y2 + 6, cx + 8, y2 + 8, Ui.mix(Look.MUTED, 0xFF0D0F14, anim[6]));   // the little space-bar dash
        }
    }

    /** One key cap: dark plate that fills with the accent colour while down; label (and CPS under it) flips to dark. */
    private static void key(GuiGraphics g, int x1, int y1, int w, int h, String label, String sub, float a) {
        int pressed = 0xFF000000 | (Look.ACCENT & 0xFFFFFF);
        Ui.panel(g, x1, y1, x1 + w, y1 + h, Ui.radius, Ui.mix(Look.BG, pressed, a * 0.85f), Look.EDGE, 0, false);
        int text = Ui.mix(Look.TEXT, 0xFF0D0F14, a);
        if (label.isEmpty()) return;
        int cx = x1 + w / 2;
        if (sub == null) {
            SdfText.ui(g, label, cx - SdfText.w(label) / 2f, y1 + (h - 8) / 2f, text, false);
        } else {
            SdfText.ui(g, label, cx - SdfText.w(label) / 2f, y1 + 3, text, false);
            int sc = Ui.mix(Look.MUTED, 0xFF0D0F14, a);
            SdfText.ui(g, sub, cx - SdfText.w(sub) / 2f, y1 + 12, sc, false);
        }
    }
}
