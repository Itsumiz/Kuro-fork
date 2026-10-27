package dev.kuro.client.module.impl;

import dev.kuro.client.util.SdfText;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Name, ping, health and armour durability floating above players' heads. */
public class PlayerInfo extends Module {
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    private final Setting.Num range = add(new Setting.Num("Range", 128, 16, 256, 8));
    private final Setting.Bool showName = add(new Setting.Bool("Name", true));
    private final Setting.Bool ping = add(new Setting.Bool("Ping", true));
    private final Setting.Bool distance = add(new Setting.Bool("Distance", false));
    private final Setting.Bool held = add(new Setting.Bool("Held items", false));
    private final double[] o = new double[2];

    public PlayerInfo() { super("Player Info", Category.RENDER); }

    @Override
    public void onHud(GuiGraphics g) {
        boolean cam = Freecam.active();
        Vec3 c = Projector.camPos;
        double max2 = range.value * range.value;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player && !cam) continue;
            if (p.isSpectator()) continue;
            Vec3 pos = p.getPosition(Projector.tickProgress);
            double dx = pos.x - c.x, dy = pos.y - c.y, dz = pos.z - c.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            if (!Projector.project(pos.x, pos.y + p.getBbHeight() + 0.9, pos.z, o)) continue;
            drawTag(g, p, (int) o[0], (int) o[1]);
        }
    }

    private void drawTag(GuiGraphics g, Player p, int cx, int bottom) {
        Font tr = mc.font;

        List<ItemStack> stacks = new ArrayList<>();
        for (EquipmentSlot s : ARMOR) stacks.add(p.getItemBySlot(s));
        if (held.value) { stacks.add(p.getMainHandItem()); stacks.add(p.getOffhandItem()); }
        stacks.removeIf(ItemStack::isEmpty);

        int latency = 0;
        if (ping.value && mc.getConnection() != null) {
            net.minecraft.client.multiplayer.PlayerInfo e = mc.getConnection().getPlayerInfo(p.getUUID());
            if (e != null) latency = e.getLatency();
        }
        boolean nameLine = showName.value || latency > 0 || distance.value;

        int total = (nameLine ? 15 : 0) + 11 + (stacks.isEmpty() ? 0 : 30);
        int y = bottom - total - 2;

        if (nameLine) {
            // name in white, then ping (green / yellow / red by quality) and distance in grey, centred as one line
            String name = showName.value ? p.getName().getString() : "";
            String pg = latency > 0 ? (name.isEmpty() ? "" : "  ") + latency + "ms" : "";
            String ds = distance.value
                    ? (name.isEmpty() && pg.isEmpty() ? "" : "  ") + Math.round(mc.player.distanceTo(p)) + "m" : "";
            int lw = SdfText.w(name) + SdfText.w(pg) + SdfText.w(ds);
            int x = cx - lw / 2;
            Look.panel(g, x - 9, y - 3, x + lw + 5, y + 10, Look.health(p.getHealth() / Math.max(1f, p.getMaxHealth())));
            SdfText.ui(g, name, x, y, Look.TEXT, false);
            x += SdfText.w(name);
            SdfText.ui(g, pg, x, y, latency < 80 ? Look.GOOD : latency < 150 ? Look.WARN : Look.BAD, false);
            x += SdfText.w(pg);
            SdfText.ui(g, ds, x, y, Look.MUTED, false);
            y += 15;
        }

        float hp = p.getHealth(), max = Math.max(1f, p.getMaxHealth()), abs = p.getAbsorptionAmount();
        String hs = String.format("%.1f/%.0f (%d%%)", hp, max, Math.round(hp / max * 100f));
        if (abs > 0) hs += String.format(" +%.0f", abs);
        center(g, tr, hs, cx, y, tierColor(hp / max));
        y += 11;

        int slot = 20;
        int x = cx - stacks.size() * slot / 2 + (slot - 16) / 2;
        for (ItemStack st : stacks) {
            g.renderItem(st, x, y);
            if (st.isDamageableItem() && st.getMaxDamage() > 0) {
                float frac = 1f - (float) st.getDamageValue() / st.getMaxDamage();
                int col = tierColor(frac);
                dev.kuro.client.util.Look.bar(g, x, y + 17, 16, 2, frac, col);
                center(g, tr, String.valueOf(Math.round(frac * 100f)), x + 8, y + 21, col);
            }
            x += slot;
        }
    }

    private static int tierColor(float frac) {
        return Look.health(frac);
    }

    private static void center(GuiGraphics g, Font tr, String s, int cx, int y, int color) {
        SdfText.ui(g, s, cx - SdfText.w(s) / 2, y, color, true);
    }
}
