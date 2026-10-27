package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug output that survives the usual ways chat messages vanish: it always goes to latest.log, and in game it is
 * added straight to the chat HUD (bypassing the "chat visibility" filter that LocalPlayer.displayClientMessage obeys),
 * on the render thread.
 */
public final class DebugChat {
    private static final Logger LOG = LoggerFactory.getLogger("kuro");
    private DebugChat() {}

    public static void say(String tag, String msg) {
        LOG.info("[{}] {}", tag, msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Component c = Component.literal("§7[" + tag + "] §f" + msg);
        Runnable r = () -> { try { mc.gui.getChat().addMessage(c); } catch (Throwable t) { LOG.warn("debug chat failed", t); } };
        if (mc.isSameThread()) r.run(); else mc.execute(r);
    }
}
