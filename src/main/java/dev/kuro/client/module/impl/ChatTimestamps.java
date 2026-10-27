package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Prefixes every chat line with the real-world time, e.g. "[14:32:07]". Display only: nothing is changed in what is sent or logged by the server. */
public class ChatTimestamps extends Module {
    private static final DateTimeFormatter T24 = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter T24S = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter T12 = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter T12S = DateTimeFormatter.ofPattern("h:mm:ss a");
    private static ChatTimestamps instance;

    private final Setting.Bool clock24 = add(new Setting.Bool("24h Clock", true));
    private final Setting.Bool seconds = add(new Setting.Bool("Seconds", true));

    public ChatTimestamps() { super("Chat Timestamps", Category.MISC); instance = this; }

    /** Called by ChatComponentMixin for every message added to the chat HUD. */
    public static Component stamp(Component message) {
        ChatTimestamps t = instance;
        if (t == null || !t.isEnabled()) return message;
        DateTimeFormatter f = t.clock24.value ? (t.seconds.value ? T24S : T24) : (t.seconds.value ? T12S : T12);
        // an empty parent keeps the message's own colours: a coloured parent would be inherited by every unstyled part of it
        return Component.empty()
                .append(Component.literal("[" + LocalTime.now().format(f) + "] ").withStyle(ChatFormatting.GRAY))
                .append(message);
    }
}
