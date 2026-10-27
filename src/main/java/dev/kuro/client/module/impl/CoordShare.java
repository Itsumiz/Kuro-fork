package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.level.Level;

/**
 * Bind a key to this: each press copies your coordinates to the clipboard and/or posts them in chat. It never stays "on".
 * Also available as the ".coords" command (".coords chat" always posts to chat).
 */
public class CoordShare extends Module {
    private static CoordShare instance;

    private final Setting.Num mode = add(new Setting.Num("Action", 0, 0, 2, 1).labelled("Copy", "Chat", "Copy + Chat"));
    private final Setting.Bool dimension = add(new Setting.Bool("Dimension", true));
    private final Setting.Bool other = add(new Setting.Bool("Nether / Overworld equivalent", false));

    public CoordShare() { super("Coord Share", Category.MISC); instance = this; }

    public static CoordShare get() { return instance; }

    @Override public void toggle() { share(false); }
    @Override public void setEnabled(boolean value) { if (value) share(false); }

    /** @param forceChat post to chat whatever the Action setting says (used by ".coords chat") */
    public void share(boolean forceChat) {
        if (mc.player == null || mc.level == null) return;
        String text = format();
        int m = mode.intValue();
        boolean copy = !forceChat && (m == 0 || m == 2) || forceChat && m == 2;
        boolean chat = forceChat || m == 1 || m == 2;
        if (copy) mc.keyboardHandler.setClipboard(text);
        if (chat) mc.player.connection.sendChat(text);

        String verb = copy && chat ? "Copied and sent" : chat ? "Sent to chat" : "Copied";
        if (Notifications.active()) Notifications.info(verb, text, true);
        else CommandManager.say(verb + ": " + text);
    }

    private String format() {
        int x = (int) Math.floor(mc.player.getX()), y = (int) Math.floor(mc.player.getY()), z = (int) Math.floor(mc.player.getZ());
        StringBuilder sb = new StringBuilder("X: ").append(x).append(" Y: ").append(y).append(" Z: ").append(z);
        var dim = mc.level.dimension();
        boolean nether = dim == Level.NETHER, overworld = dim == Level.OVERWORLD;
        if (dimension.value) sb.append(" (").append(overworld ? "Overworld" : nether ? "Nether" : dim == Level.END ? "End" : "Custom").append(")");
        if (other.value && (overworld || nether)) {
            int ox = Math.floorDiv(x, overworld ? 8 : 1) * (nether ? 8 : 1), oz = Math.floorDiv(z, overworld ? 8 : 1) * (nether ? 8 : 1);
            sb.append(" [").append(overworld ? "Nether" : "Overworld").append(": ").append(ox).append(", ").append(oz).append("]");
        }
        return sb.toString();
    }
}
