package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Freecam. The camera is moved by Camera/Entity mixins; the real player is frozen by
 * releasing its movement keys every tick while we read the physical keys ourselves.
 */
public class Freecam extends Module {
    private static Freecam instance;

    public final Setting.Num speed = add(new Setting.Num("Speed", 0.8, 0.1, 4.0, 0.1));

    public double x, y, z, px, py, pz;
    public float yaw, pitch;
    private Object owner;

    public Freecam() {
        super("Freecam", Category.PLAYER);
        instance = this;
    }

    public static Freecam get() { return instance; }
    public static boolean active() { return instance != null && instance.isEnabled(); }

    @Override
    protected void onEnable() {
        if (mc.player == null) { setEnabled(false); return; }
        owner = mc.player;
        Vec3 e = mc.player.getEyePosition();
        x = px = e.x; y = py = e.y; z = pz = e.z;
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
    }

    @Override
    protected void onDisable() {
        owner = null;
        KeyMapping.setAll(); // re-sync keys that were still held
    }

    @Override
    public void onPreTick() {
        // leaving the world / changing dimension replaces the player object
        if (mc.player == null || mc.player != owner) { setEnabled(false); return; }

        px = x; py = y; pz = z;
        Options o = mc.options;

        // keep the real player frozen
        for (KeyMapping k : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight,
                o.keyJump, o.keyShift, o.keySprint, o.keyAttack, o.keyUse}) {
            k.setDown(false);
        }
        if (mc.screen != null) return;

        long w = mc.getWindow().handle();
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double fx = -Math.sin(yr) * Math.cos(pr), fy = -Math.sin(pr), fz = Math.cos(yr) * Math.cos(pr);
        double rx = -Math.cos(yr), rz = -Math.sin(yr);

        double f = (down(w, o.keyUp) ? 1 : 0) - (down(w, o.keyDown) ? 1 : 0);
        double s = (down(w, o.keyRight) ? 1 : 0) - (down(w, o.keyLeft) ? 1 : 0);
        double u = (down(w, o.keyJump) ? 1 : 0) - (down(w, o.keyShift) ? 1 : 0);

        double dx = fx * f + rx * s, dy = fy * f + u, dz = fz * f + rz * s;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1e-6) {
            double sp = speed.value * (down(w, o.keySprint) ? 2.0 : 1.0);
            x += dx / len * sp; y += dy / len * sp; z += dz / len * sp;
        }
    }

    private static boolean down(long window, KeyMapping k) {
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(k);
        return key.getType() == InputConstants.Type.KEYSYM
                && GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
    }

    /** Called by EntityMixin instead of turning the real player. */
    public void look(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = Mth.clamp(pitch + (float) (dy * 0.15), -90f, 90f);
    }

    public double lx(float t) { return px + (x - px) * t; }
    public double ly(float t) { return py + (y - py) * t; }
    public double lz(float t) { return pz + (z - pz) * t; }
}
