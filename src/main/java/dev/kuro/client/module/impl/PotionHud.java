package dev.kuro.client.module.impl;

import dev.kuro.client.gui.HudEditScreen;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Your active potion effects with level and time left, as a movable HUD plate. */
public class PotionHud extends Module implements HudElement {
    private static final String[] ROMAN = {"", "", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    private static final int ROW = 12;

    private final Setting.Num x = add(new Setting.Num("X", 6, 0, 4000, 1));
    private final Setting.Num y = add(new Setting.Num("Y", 130, 0, 4000, 1));
    private final Setting.Bool colorDot = add(new Setting.Bool("Effect colour dot", true));
    private final Setting.Bool warn = add(new Setting.Bool("Warn when running out", true));

    private int lastW = 90, lastH = 20;

    public PotionHud() { super("Potion HUD", Category.RENDER); }

    @Override public Setting.Num hudX() { return x; }
    @Override public Setting.Num hudY() { return y; }
    @Override public int hudW() { return lastW; }
    @Override public int hudH() { return lastH; }
    @Override public String hudName() { return name; }

    private static String level(int amplifier) {
        int lvl = amplifier + 1;
        return lvl < ROMAN.length ? ROMAN[lvl] : String.valueOf(lvl);
    }

    private static String time(MobEffectInstance e) {
        if (e.isInfiniteDuration()) return "Inf";
        int s = (e.getDuration() + 19) / 20;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    @Override
    public void onHud(GuiGraphics g) {
        if (mc.player == null) return;
        List<MobEffectInstance> effects = new ArrayList<>(mc.player.getActiveEffects());
        effects.sort(Comparator.comparingInt((MobEffectInstance e) -> e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration()).reversed());

        List<String> names = new ArrayList<>(), times = new ArrayList<>();
        for (MobEffectInstance e : effects) {
            String lv = level(e.getAmplifier());
            names.add(e.getEffect().value().getDisplayName().getString() + (lv.isEmpty() ? "" : " " + lv));
            times.add(time(e));
        }
        if (effects.isEmpty()) {
            if (!HudEditScreen.editing()) return;
            names.add("Speed II"); times.add("1:30");         // placeholder so the plate can be found and moved
        }

        int maxName = 0, maxTime = 0;
        for (String s : names) maxName = Math.max(maxName, SdfText.w(s));
        for (String s : times) maxTime = Math.max(maxTime, SdfText.w(s));
        int n = names.size();
        int w = Math.max(90, 16 + maxName + 14 + maxTime + 8), h = n * ROW + 8;
        lastW = w; lastH = h;
        int x1 = HudElement.clampX(x, w), y1 = HudElement.clampY(y, h);
        Look.plate(g, x1, y1, x1 + w, y1 + h);

        for (int i = 0; i < n; i++) {
            int ry = y1 + 5 + i * ROW;
            boolean real = !effects.isEmpty();
            MobEffectInstance e = real ? effects.get(i) : null;
            if (colorDot.value) {
                int c = real ? 0xFF000000 | (e.getEffect().value().getColor() & 0xFFFFFF) : Look.MUTED;
                g.fill(x1 + 7, ry + 2, x1 + 12, ry + 7, c);
            }
            boolean harmful = real && e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL;
            SdfText.ui(g, names.get(i), x1 + 16, ry, harmful ? Look.BAD : Look.TEXT, false);

            int tc = Look.MUTED;
            if (real && warn.value && !e.isInfiniteDuration()) {
                int secs = e.getDuration() / 20;
                tc = secs < 10 ? Look.BAD : secs < 30 ? Look.WARN : Look.MUTED;
            }
            SdfText.ui(g, times.get(i), x1 + w - 8 - SdfText.w(times.get(i)), ry, tc, false);
        }
    }
}
