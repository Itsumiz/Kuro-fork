package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filters NPC / watchdog-style fake players out of combat targeting.
 * Ported (simplified) from Respect's AntiBot checks.
 */
public class AntiBot extends Module {
    private static AntiBot INSTANCE;

    private final Setting.Bool tabList = add(new Setting.Bool("Tab list", true));
    private final Setting.Bool validName = add(new Setting.Bool("Valid name", true));
    private final Setting.Bool noColorCodes = add(new Setting.Bool("No color codes", true));
    private final Setting.Bool pingCheck = add(new Setting.Bool("Ping present", false));

    private static final Pattern NAME_OK = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    public AntiBot() {
        super("Anti Bot", Category.COMBAT);
        INSTANCE = this;
    }

    public static boolean isBot(Entity e) {
        if (!(e instanceof Player p)) return false;
        if (FakePlayer.isFake(e)) return false;   // the test dummy must stay targetable
        if (INSTANCE == null || !INSTANCE.isEnabled()) return false;
        return INSTANCE.check(p);
    }

    public static boolean isBotPlayer(Player p) {
        return isBot(p);
    }

    private boolean check(Player p) {
        if (p == mc.player) return false;
        String name = p.getName().getString();
        if (noColorCodes.value && (name.indexOf('§') >= 0 || name.indexOf('&') >= 0)) return true;
        if (validName.value && !NAME_OK.matcher(name.replace("§", "").trim()).matches()) return true;

        if (tabList.value || pingCheck.value) {
            if (mc.getConnection() == null) return false;
            UUID id = p.getUUID();
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (tabList.value && info == null) return true;
            if (pingCheck.value && info != null && info.getLatency() < 0) return true;
        }
        return false;
    }
}
