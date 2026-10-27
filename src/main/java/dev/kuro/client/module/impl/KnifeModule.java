package dev.kuro.client.module.impl;

import dev.kuro.client.knife.KnifeRenderer;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.DebugChat;
import org.lwjgl.glfw.GLFW;

/**
 * Replaces the sword in your first-person hand with a knife (karambit, butterfly, katana or a CS2 knife), drawn with your real arm, plus a
 * draw animation when you switch to it, a hold-to-loop inspect on a key, and an optional motion trail.
 */
public class KnifeModule extends Module {
    private static KnifeModule instance;

    private final Setting.Num knife = add(new Setting.Num("Knife", 1, 1, KnifeRenderer.KNIVES.length, 1).labelled(KnifeRenderer.KNIVES));
    private final Setting.Num size = add(new Setting.Num("Size", 1.0, 0.5, 2.0, 0.05));
    private final Setting.Bool trail = add(new Setting.Bool("Trail", true));
    private final Setting.Num trailLength = add(new Setting.Num("Trail length (s)", 0.2, 0.1, 0.8, 0.05));
    private final Setting.Num inspectKey = add(new Setting.Num("Inspect key code (GLFW)", GLFW.GLFW_KEY_G, 32, 348, 1));
    private final Setting.Button bindKey = add(new Setting.Button("Bind inspect key (click, then press a key)", this::startBinding));
    private final Setting.Num offX = add(new Setting.Num("Offset X", 0.0, -0.4, 0.4, 0.01));
    private final Setting.Num offY = add(new Setting.Num("Offset Y", 0.0, -0.4, 0.4, 0.01));
    private final Setting.Num offZ = add(new Setting.Num("Offset Z (+ = closer)", 0.0, -0.4, 0.4, 0.01));
    private final Setting.Bool material = add(new Setting.Bool("Match sword material", true));
    private final Setting.Bool deploy = add(new Setting.Bool("Draw animation", true));
    private final Setting.Bool cancelOnSwing = add(new Setting.Bool("Hit cancels inspect", true));
    private final Setting.Bool attackAnimation = add(new Setting.Bool("Attack animation", true));

    // key binding: after the button is clicked, the next fresh key press becomes the inspect key
    private boolean listening;
    private final boolean[] wasDown = new boolean[GLFW.GLFW_KEY_LAST + 1];

    public KnifeModule() {
        super("Knife", Category.RENDER);
        instance = this;
    }

    public static boolean active() { return instance != null && instance.isEnabled(); }
    public static KnifeModule get() { return instance; }

    public float size() { return (float) size.value; }
    public boolean matchMaterial() { return material.value; }
    public float offX() { return (float) offX.value; }
    public float offY() { return (float) offY.value; }
    public float offZ() { return (float) offZ.value; }
    public int knife() { return knife.intValue(); }
    /** False = the hand and knife stay put when you swing / hit (vanilla's attack motion is switched off). */
    public boolean attackAnimation() { return attackAnimation.value; }
    /** Trail length in seconds, 0 when the trail is switched off. */
    public float trailSeconds() { return trail.value ? (float) trailLength.value : 0.0f; }

    @Override
    protected void onEnable() { KnifeRenderer.reset(); }

    @Override
    protected void onDisable() { KnifeRenderer.reset(); listening = false; }

    private void startBinding() {
        if (!isEnabled()) {
            DebugChat.say("Knife", "Turn the Knife module on first, then click Bind again.");
            return;
        }
        listening = true;
        snapshotKeys();                                   // keys already held don't count
        DebugChat.say("Knife", "Press the key for inspect (Esc cancels).");
    }

    private void snapshotKeys() {
        long win = mc.getWindow().handle();
        for (int k = GLFW.GLFW_KEY_SPACE; k <= GLFW.GLFW_KEY_LAST; k++) wasDown[k] = GLFW.glfwGetKey(win, k) == GLFW.GLFW_PRESS;
    }

    /** While listening: first key that goes from up to down. Returns it, or -1. */
    private int freshKey() {
        long win = mc.getWindow().handle();
        int found = -1;
        for (int k = GLFW.GLFW_KEY_SPACE; k <= GLFW.GLFW_KEY_LAST; k++) {
            boolean down = GLFW.glfwGetKey(win, k) == GLFW.GLFW_PRESS;
            if (down && !wasDown[k] && found < 0) found = k;
            wasDown[k] = down;
        }
        return found;
    }

    static String keyName(int key) {
        String n = GLFW.glfwGetKeyName(key, 0);
        if (n != null) return n.toUpperCase(java.util.Locale.ROOT);
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "Left Shift";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "Left Ctrl";
            case GLFW.GLFW_KEY_LEFT_ALT -> "Left Alt";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "Right Ctrl";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "Right Alt";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "Caps Lock";
            default -> "key " + key;
        };
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.getWindow() == null) return;
        if (listening) {
            int k = freshKey();
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                listening = false;
                DebugChat.say("Knife", "Bind cancelled (inspect key is still " + keyName(inspectKey.intValue()) + ").");
            } else if (k == GLFW.GLFW_KEY_RIGHT_SHIFT) {
                DebugChat.say("Knife", "Right Shift opens the menu, pick another key.");
            } else if (k >= 0) {
                listening = false;
                inspectKey.value = k;
                DebugChat.say("Knife", "Inspect key set to " + keyName(k) + ".");
            }
            KnifeRenderer.tick(false, deploy.value, cancelOnSwing.value);
            return;
        }
        boolean down = mc.screen == null && GLFW.glfwGetKey(mc.getWindow().handle(), inspectKey.intValue()) == GLFW.GLFW_PRESS;
        KnifeRenderer.tick(down, deploy.value, cancelOnSwing.value);
    }
}
