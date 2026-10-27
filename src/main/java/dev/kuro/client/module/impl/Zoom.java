package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Hold a key to zoom in (default C), scroll to change how far. The field of view is eased in and out every frame, and the
 * mouse turns the view less while you are zoomed so aiming stays steady at any zoom.
 *
 * The FOV itself is changed in GameRendererMixin. Projector / WorldGfx divide by {@link #factor()} so ESP overlays stay on
 * their blocks while zoomed.
 */
public class Zoom extends Module {
    private static Zoom instance;

    private final Setting.Num zoom = add(new Setting.Num("Zoom (x)", 4.0, 1.5, 30.0, 0.5));
    private final Setting.Bool hold = add(new Setting.Bool("Hold to use", true));
    private final Setting.Bool smooth = add(new Setting.Bool("Smooth zoom", true));
    private final Setting.Bool scroll = add(new Setting.Bool("Scroll to change zoom", true));
    private final Setting.Bool slow = add(new Setting.Bool("Slow mouse while zoomed", true));

    private float cur = 1f;          // the zoom factor being shown right now (1 = no zoom)
    private long lastNs;

    public Zoom() {
        super("Zoom", Category.RENDER);
        instance = this;
        key = GLFW.GLFW_KEY_C;
    }

    @Override public boolean holdToUse() { return hold.value; }

    /** 1 = no zoom. Stays above 1 while the zoom eases back out after the module was switched off. */
    public static float factor() { return instance == null ? 1f : instance.cur; }

    /** Once per frame (from GameRendererMixin): moves the shown zoom toward the wanted one. */
    public static void frame() {
        if (instance != null) instance.step();
    }

    private void step() {
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 1f / 60f : Math.min((now - lastNs) / 1.0E9f, 0.1f);
        lastNs = now;
        float target = isEnabled() && mc.player != null ? (float) zoom.value : 1f;
        if (smooth.value) {
            cur += (target - cur) * (1f - (float) Math.exp(-dt * 12f));
            if (Math.abs(target - cur) < 0.005f) cur = target;
        } else {
            cur = target;
        }
    }

    /** The vanilla field of view (degrees) narrowed by the zoom factor; the same maths as a longer lens. */
    public static float fov(float vanilla) {
        float f = factor();
        if (f <= 1.0001f) return vanilla;
        return (float) Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(vanilla) / 2.0) / f));
    }

    /** Multiplier for mouse movement: smaller while zoomed in. */
    public static double sensScale() {
        Zoom z = instance;
        if (z == null || !z.slow.value || z.cur <= 1.0001f) return 1.0;
        return 1.0 / Math.pow(z.cur, 0.85);
    }

    /** Mouse wheel while zooming changes the zoom instead of the hotbar. Returns true = the scroll was used. */
    public static boolean onScroll(double dy) {
        Zoom z = instance;
        if (z == null || !z.isEnabled() || !z.scroll.value || Math.abs(dy) < 1.0E-6 || mc.screen != null) return false;
        z.zoom.value = Mth.clamp(z.zoom.value * (dy > 0 ? 1.15 : 1.0 / 1.15), z.zoom.min, z.zoom.max);
        return true;
    }

    @Override protected void onEnable() { lastNs = 0L; }
}
