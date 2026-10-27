package dev.kuro.client.echo.handlers;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kuro.client.mixin.EchoKeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import static dev.kuro.client.echo.Imports.mc;

/** The bind-state part of hack.echo.client.handlers.InputHandler (binds: keyboard = GLFW code, mouse = 0x80000000 | button). */
public final class InputHandler {
    private InputHandler() {}

    public static boolean isKeyDown(int key) {
        if (mc == null || mc.getWindow() == null) return false;
        return GLFW.glfwGetKey(mc.getWindow().handle(), key) == GLFW.GLFW_PRESS;
    }

    public static boolean isBindDown(int bind) {
        if (bind == -1 || bind == 0) return false;
        if ((bind & 0x80000000) != 0) {
            int mb = bind & 0xFF;
            if (mc == null || mc.getWindow() == null) return false;
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), mb) == GLFW.GLFW_PRESS;
        }
        return isKeyDown(bind);
    }

    public static boolean isBindDown(KeyMapping keyMapping) {
        if (keyMapping == null) return false;
        return isBindDown(keyToBind(((EchoKeyMappingAccessor) keyMapping).kuro$getKey()));
    }

    public static void incrementClickCountForKeyMapping(KeyMapping keyMapping) {
        if (keyMapping == null) return;
        InputConstants.Key key = ((EchoKeyMappingAccessor) keyMapping).kuro$getKey();
        if (key != null) KeyMapping.click(key);
    }

    public static int keyToBind(InputConstants.Key key) {
        if (key == null) return -1;
        if (key.getType() == InputConstants.Type.MOUSE) return 0x80000000 | key.getValue();
        return key.getValue();
    }
}
