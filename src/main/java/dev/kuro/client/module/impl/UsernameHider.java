package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Username Hider (client side only): while on, your own username is replaced by a fake name everywhere text is drawn
 * on your screen (chat, tab list, name tags, scoreboard, menus, signs). The server and other players still see your
 * real name. Type a new fake name in the settings box and press Enter; it is remembered in config/kuro-fakename.txt.
 */
public class UsernameHider extends Module {
    private static UsernameHider instance;
    private static final String DEFAULT_NAME = "Player";

    private String fake = DEFAULT_NAME;
    private String cachedReal = "";
    private Pattern pattern;

    public UsernameHider() {
        super("Username Hider", Category.MISC);
        instance = this;
        load();
        add(new Setting.Text("Type fake name", this::setFake));
        add(new Setting.Button("Show fake name", () -> CommandManager.say("Your name is shown as: " + fake)));
        add(new Setting.Button("Reset fake name", () -> setFake(DEFAULT_NAME)));
    }

    private void setFake(String raw) {
        String n = raw.trim();
        if (n.isEmpty() || n.length() > 32) { CommandManager.say("Fake name must be 1-32 characters"); return; }
        fake = n;
        save();
        CommandManager.say("Fake name set to " + n);
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("kuro-fakename.txt"); }

    private void load() {
        try {
            Path p = file();
            if (Files.exists(p)) {
                String s = Files.readString(p).trim();
                if (!s.isEmpty() && s.length() <= 32) fake = s;
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try { Files.writeString(file(), fake); } catch (Exception ignored) {}
    }

    /** Called by TextVisitFactoryMixin for every string that is about to be drawn or measured. */
    public static String apply(String text) {
        UsernameHider h = instance;
        if (h == null || !h.isEnabled() || text == null || text.isEmpty()) return text;
        return h.replace(text);
    }

    private String replace(String text) {
        String real;
        try { real = Minecraft.getInstance().getUser().getName(); } catch (Exception e) { return text; }
        if (real == null || real.isEmpty() || text.length() < real.length()) return text;
        if (!real.equals(cachedReal)) { cachedReal = real; pattern = Pattern.compile(Pattern.quote(real), Pattern.CASE_INSENSITIVE); }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.replaceAll(Matcher.quoteReplacement(fake)) : text;
    }
}
