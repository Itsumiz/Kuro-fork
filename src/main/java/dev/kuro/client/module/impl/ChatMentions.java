package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highlights your username in chat, plays a ping and shows a toast when someone mentions you.
 * Only chat lines count (a "<Name> ..." / "Name: ..." / "Name » ..." sender part is found first, and your own name there,
 * or in system lines like "Name joined the game", is ignored). Display only: nothing is sent or changed on the server.
 */
public class ChatMentions extends Module {
    private static ChatMentions instance;
    private static String cachedName = "";
    private static Pattern cachedPattern;
    private static long lastPing;

    private final Setting.Hue color = add(new Setting.Hue("Highlight colour", 0.13f).wheel(0.85f, 1f));
    private final Setting.Bool bold = add(new Setting.Bool("Bold", true));
    private final Setting.Bool sound = add(new Setting.Bool("Ping sound", true));
    private final Setting.Bool toast = add(new Setting.Bool("Toast", true));

    public ChatMentions() { super("Chat Mentions", Category.MISC); instance = this; }

    private static Pattern pattern(String name) {
        if (!name.equals(cachedName) || cachedPattern == null) {
            cachedName = name;
            cachedPattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
        }
        return cachedPattern;
    }

    /** End of the "who said it" part (index after the first > : or » in the first 48 chars), or -1 when this is not a chat line. */
    private static int senderEnd(String plain) {
        int lim = Math.min(plain.length(), 48);
        for (int i = 0; i < lim; i++) {
            char c = plain.charAt(i);
            if (c == '>' || c == ':' || c == '»') return i + 1;
        }
        return -1;
    }

    /** Called by ChatComponentMixin for every message added to the chat HUD. */
    public static Component process(Component msg) {
        ChatMentions m = instance;
        if (m == null || !m.isEnabled() || mc.player == null) return msg;
        String name = mc.player.getName().getString();
        if (name.isEmpty()) return msg;
        String plain = msg.getString();
        int zone = senderEnd(plain);
        if (zone < 0) return msg;
        Pattern pat = pattern(name);
        Matcher probe = pat.matcher(plain);
        boolean hit = false;
        while (probe.find()) if (probe.start() >= zone) { hit = true; break; }
        if (!hit) return msg;

        TextColor tc = TextColor.fromRgb(m.color.rgb());
        MutableComponent out = Component.empty();
        int[] offset = {0};
        msg.<Boolean>visit((style, s) -> {
            Matcher mm = pat.matcher(s);
            int last = 0;
            while (mm.find()) {
                if (offset[0] + mm.start() < zone) continue;
                if (mm.start() > last) out.append(Component.literal(s.substring(last, mm.start())).withStyle(style));
                out.append(Component.literal(mm.group()).withStyle(style.withColor(tc).withBold(m.bold.value ? Boolean.TRUE : style.isBold())));
                last = mm.end();
            }
            if (last < s.length()) out.append(Component.literal(s.substring(last)).withStyle(style));
            offset[0] += s.length();
            return Optional.<Boolean>empty();
        }, Style.EMPTY);

        m.alert(plain);
        return out;
    }

    private void alert(String plain) {
        long now = System.currentTimeMillis();
        if (now - lastPing < 1000) return;                           // a burst of mentions pings once
        lastPing = now;
        if (sound.value) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
        if (toast.value) Notifications.info("Mentioned", plain.length() > 40 ? plain.substring(0, 40) + "..." : plain, true);
    }
}
