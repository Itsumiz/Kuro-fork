package dev.kuro.client.module.impl;

import dev.kuro.client.gui.HudEditScreen;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Your own armour (and optionally offhand / held item) with durability, as a movable HUD plate. */
public class ArmorHud extends Module implements HudElement {
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    private static final int PAD = 4;

    private final Setting.Num x = add(new Setting.Num("X", 6, 0, 4000, 1));
    private final Setting.Num y = add(new Setting.Num("Y", 70, 0, 4000, 1));
    private final Setting.Num layout = add(new Setting.Num("Layout", 0, 0, 1, 1).labelled("Vertical", "Horizontal"));
    private final Setting.Bool percent = add(new Setting.Bool("Durability %", true));
    private final Setting.Bool bar = add(new Setting.Bool("Durability bar", true));
    private final Setting.Bool offhand = add(new Setting.Bool("Offhand", false));
    private final Setting.Bool held = add(new Setting.Bool("Held item", false));

    private int lastW = 60, lastH = 16;

    public ArmorHud() { super("Armor HUD", Category.RENDER); }

    @Override public Setting.Num hudX() { return x; }
    @Override public Setting.Num hudY() { return y; }
    @Override public int hudW() { return lastW; }
    @Override public int hudH() { return lastH; }
    @Override public String hudName() { return name; }

    @Override
    public void onHud(GuiGraphics g) {
        if (mc.player == null) return;
        List<ItemStack> stacks = new ArrayList<>();
        for (EquipmentSlot s : SLOTS) stacks.add(mc.player.getItemBySlot(s));
        if (held.value) stacks.add(mc.player.getMainHandItem());
        if (offhand.value) stacks.add(mc.player.getOffhandItem());
        stacks.removeIf(ItemStack::isEmpty);

        if (stacks.isEmpty()) {
            if (!HudEditScreen.editing()) return;          // nothing to show; in the layout editor keep a handle to drag
            lastW = SdfText.w(name) + 16; lastH = 16;
            int x1 = HudElement.clampX(x, lastW), y1 = HudElement.clampY(y, lastH);
            Look.plate(g, x1, y1, x1 + lastW, y1 + lastH);
            SdfText.ui(g, name, x1 + 8, y1 + 4, Look.MUTED, false);
            return;
        }

        int n = stacks.size();
        boolean vertical = layout.intValue() == 0;
        int textW = SdfText.w("100%");
        int w, h;
        if (vertical) {
            w = PAD * 2 + 16 + (percent.value ? 4 + textW : 0);
            h = PAD * 2 + n * 18 - 2;
        } else {
            w = PAD * 2 + n * 20 - 4;
            h = PAD * 2 + 16 + (bar.value ? 3 : 0) + (percent.value ? 10 : 0);
        }
        lastW = w; lastH = h;
        int x1 = HudElement.clampX(x, w), y1 = HudElement.clampY(y, h);
        Look.plate(g, x1, y1, x1 + w, y1 + h);

        for (int i = 0; i < n; i++) {
            ItemStack st = stacks.get(i);
            int ix = vertical ? x1 + PAD : x1 + PAD + i * 20;
            int iy = vertical ? y1 + PAD + i * 18 : y1 + PAD;
            g.renderItem(st, ix, iy);
            if (!st.isDamageableItem() || st.getMaxDamage() <= 0) continue;
            float frac = 1f - (float) st.getDamageValue() / st.getMaxDamage();
            int col = Look.health(frac);
            String pct = Math.round(frac * 100f) + "%";
            if (vertical) {
                if (bar.value) Look.bar(g, ix, iy + 16, 16, 1, frac, col);
                if (percent.value) SdfText.ui(g, pct, ix + 20, iy + 4, col, true);
            } else {
                if (bar.value) Look.bar(g, ix, iy + 17, 16, 2, frac, col);
                if (percent.value) SdfText.ui(g, pct, ix + 8 - SdfText.w(pct) / 2f, iy + (bar.value ? 20 : 17), col, true);
            }
        }
    }
}
