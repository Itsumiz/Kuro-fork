package dev.kuro.client.util;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;

public class MovementHelper {
    private static final Minecraft mc = Minecraft.getInstance();

    /**
     * Physically presses a keybinding.
     */
    public static void setKey(KeyMapping key, boolean pressed) {
        if (key == null) return;
        key.setDown(pressed);
    }

    /**
     * Releases all movement keys.
     */
    public static void releaseAllKeys() {
        if (mc.options == null) return;
        setKey(mc.options.keyUp, false);
        setKey(mc.options.keyDown, false);
        setKey(mc.options.keyLeft, false);
        setKey(mc.options.keyRight, false);
        setKey(mc.options.keyJump, false);
        setKey(mc.options.keyShift, false);
        setKey(mc.options.keySprint, false);
    }

    /**
     * Checks if a block exists below the specific X/Z coordinate.
     */
    public static boolean hasBlockBelow(LocalPlayer player, double x, double z) {
        if (mc.level == null) return false;
        AABB box = new AABB(x - 0.3, player.getY() - 1.0, z - 0.3, x + 0.3, player.getY(), z + 0.3);
        return !mc.level.noCollision(player, box);
    }
}