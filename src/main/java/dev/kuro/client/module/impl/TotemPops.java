package dev.kuro.client.module.impl;

import dev.kuro.client.util.SdfText;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.HudPanel;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Look;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Counts Totem of Undying pops. The server tells every client when a player's totem pops (entity status 35) and when
 * an entity dies (status 3); ClientPlayNetworkHandlerMixin forwards both here. Shows a chat line on every pop, an
 * optional sound, and a small on-screen list of recent pops with their count. The count resets when that player dies.
 */
public class TotemPops extends Module {
    private static final byte STATUS_TOTEM = 35, STATUS_DEATH = 3;
    private static final SoundEvent ALERT = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("kuro", "alert"));
    private static TotemPops instance;

    private final Setting.Bool chat = add(new Setting.Bool("Chat message", true));
    private final Setting.Bool sound = add(new Setting.Bool("Alert sound", false));
    private final Setting.Bool hudList = add(new Setting.Bool("HUD list", true));
    private final Setting.Num showFor = add(new Setting.Num("List time (s)", 60, 10, 600, 10));
    private final Setting.Bool self = add(new Setting.Bool("Count yourself", false));
    private final Setting.Bool skipFriends = add(new Setting.Bool("Ignore friends", false));
    private final Setting.Bool deathMsg = add(new Setting.Bool("Death message", true));

    private static final class Pops {
        String name;
        int count;
        long last;
    }

    private final Map<UUID, Pops> pops = new HashMap<>();
    private Level lastWorld;

    private final HudPanel panel = new HudPanel(this, "Totem pops");

    public TotemPops() {
        super("Totem Pops", Category.COMBAT);
        instance = this;
    }

    @Override
    public java.util.List<HudElement> hudElements() {
        return hudList.value ? java.util.List.of(panel) : java.util.List.of();
    }

    @Override protected void onDisable() { pops.clear(); }

    @Override
    public void onTick() {
        if (mc.level != lastWorld) { lastWorld = mc.level; pops.clear(); }
    }

    /** Called from ClientPlayNetworkHandlerMixin for every entity status packet (game thread). */
    public static void onStatus(Entity e, byte status) {
        if (instance == null || !instance.isEnabled()) return;
        if (status != STATUS_TOTEM && status != STATUS_DEATH) return;
        if (e instanceof Player p) instance.handle(p, status);
    }

    private void handle(Player p, byte status) {
        if (mc.player == null) return;
        if (p == mc.player && !self.value) return;
        if (skipFriends.value && Friends.is(p)) return;
        String name = p.getName().getString();
        if (status == STATUS_TOTEM) {
            Pops e = pops.computeIfAbsent(p.getUUID(), k -> new Pops());
            e.name = name;
            e.count++;
            e.last = System.currentTimeMillis();
            if (chat.value) {
                mc.player.displayClientMessage(Component.literal("[Pops] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(name + " popped a totem (" + e.count + ")").withStyle(ChatFormatting.YELLOW)), false);
            }
            if (sound.value) mc.player.playSound(ALERT, 1.0f, 1.0f);
        } else {
            Pops e = pops.remove(p.getUUID());
            if (e != null && deathMsg.value) {
                mc.player.displayClientMessage(Component.literal("[Pops] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(name + " died after " + e.count + " pop" + (e.count == 1 ? "" : "s")).withStyle(ChatFormatting.RED)), false);
            }
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        if (!hudList.value) return;
        boolean stand = pops.isEmpty() && dev.kuro.client.gui.HudEditScreen.editing();   // layout editor: show a stand-in so the list can be moved
        if (pops.isEmpty() && !stand) return;
        long now = System.currentTimeMillis(), keep = (long) showFor.value * 1000L;
        pops.values().removeIf(p -> now - p.last > keep);
        List<Pops> list = new ArrayList<>(pops.values());
        if (list.isEmpty()) {
            if (!stand) return;
            Pops demo = new Pops();
            demo.name = "Player"; demo.count = 2; demo.last = now;
            list.add(demo);
        }
        list.sort((a, b) -> b.count != a.count ? Integer.compare(b.count, a.count) : Long.compare(b.last, a.last));
        if (list.size() > 8) list = list.subList(0, 8);

        Font tr = mc.font;
        int w = SdfText.w("Totem pops") + 16;
        for (Pops p : list) w = Math.max(w, SdfText.w(p.name) + SdfText.w("x" + p.count) + 28);
        int h = 14 + list.size() * 11;
        panel.place(4, 38, w, h);
        int x = panel.x(), y = panel.y();
        Look.panel(g, x, y, x + w, y + h, Look.WARN);
        SdfText.ui(g, "Totem pops", x + 8, y + 3, Look.MUTED, false);
        int ry = y + 14;
        for (Pops p : list) {
            SdfText.ui(g, p.name, x + 8, ry, Look.TEXT, false);
            String c = "x" + p.count;
            SdfText.ui(g, c, x + w - 7 - SdfText.w(c), ry, p.count >= 3 ? Look.BAD : Look.WARN, false);
            ry += 11;
        }
    }
}
