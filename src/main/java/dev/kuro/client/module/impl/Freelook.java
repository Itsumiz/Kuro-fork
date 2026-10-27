package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.util.Mth;

/**
 * Look around freely with the mouse while your character keeps facing (and walking) the same way, like the freelook in
 * other clients:
 *   - Hold to use (default): bind a key, hold it to look around, let go and the camera glides back to where you face.
 *   - Third person: the camera pulls back behind you while you look around and glides in again afterwards (it still
 *     stops at walls: CameraMixin only scales the game's own third-person distance).
 *   - Camera distance and the glide speed are settings.
 * The camera is advanced once per rendered frame (not per tick), so the glide is smooth at any frame rate.
 */
public class Freelook extends Module {
    private static Freelook instance;
    /** Camera angles (what you see). The real player keeps its own angles the whole time. */
    public float yaw, pitch;
    private Object owner;

    private final Setting.Bool hold = add(new Setting.Bool("Hold to use", true));
    private final Setting.Bool third = add(new Setting.Bool("Third person", true));
    private final Setting.Bool smoothReturn = add(new Setting.Bool("Smooth return", true));
    private final Setting.Num glide = add(new Setting.Num("Glide speed", 10, 3, 30, 1));
    private final Setting.Num distance = add(new Setting.Num("Camera distance", 4.0, 1.5, 8.0, 0.5));

    private float ease;            // 0..1: how far the third-person camera has pulled back
    private boolean returning;     // switched off, camera still gliding back
    private long lastNs;

    public Freelook() { super("Freelook", Category.RENDER); instance = this; }

    public static Freelook get() { return instance; }

    @Override public boolean holdToUse() { return hold.value; }

    /** The mouse steers the camera (switched on). Not while Freecam is on: Freecam steers the camera itself. */
    public static boolean active() { return instance != null && instance.isEnabled() && !Freecam.active(); }

    /** The camera is detached from the player: switched on, or still gliding back after it was switched off. */
    public static boolean showing() {
        return instance != null && !Freecam.active() && (instance.isEnabled() || instance.returning || instance.ease > 0f);
    }

    /** Freelook wants the game's third-person camera right now. */
    public static boolean thirdPerson() { return showing() && instance.third.value && instance.ease > 0.001f; }

    public float ease() { return ease; }
    public float distanceScale() { return (float) (distance.value / 4.0); }
    /** How far behind the player the camera sits at full pull-back, in blocks. */
    public float distanceBlocks() { return (float) distance.value; }

    /** Once per rendered frame (from CameraMixin): moves the pull-back and the glide-back forward in real time. */
    public static void frameStatic() { if (instance != null) instance.frame(); }

    private void frame() {
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 1f / 60f : Math.min((now - lastNs) / 1.0E9f, 0.1f);
        lastNs = now;
        if (mc.player == null || mc.player != owner) { ease = 0f; returning = false; return; }

        boolean on = isEnabled();
        if (!on && !smoothReturn.value) { ease = 0f; returning = false; return; }

        float k = 1f - (float) Math.exp(-dt * glide.intValue());
        float target = on && third.value ? 1f : 0f;
        ease += (target - ease) * k;
        if (Math.abs(target - ease) < 0.01f) ease = target;

        if (!on && returning) {
            float dy = Mth.wrapDegrees(mc.player.getYRot() - yaw), dp = mc.player.getXRot() - pitch;
            yaw += dy * k;
            pitch += dp * k;
            if (Math.abs(dy) < 0.3f && Math.abs(dp) < 0.3f && ease == 0f) returning = false;
        }
    }

    @Override
    protected void onEnable() {
        if (mc.player == null) { setEnabled(false); return; }
        owner = mc.player;
        if (!returning) {                       // switched on again while gliding back: carry on from where the camera is
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
        }
        returning = false;
        lastNs = 0L;
    }

    @Override
    protected void onDisable() {
        returning = smoothReturn.value && mc.player != null && mc.player == owner;
        if (!returning) ease = 0f;
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.player != owner) setEnabled(false);   // new world / dimension
    }

    /** Called by EntityMixin instead of turning the real player. */
    public void look(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = Mth.clamp(pitch + (float) (dy * 0.15), -90f, 90f);
    }
}
