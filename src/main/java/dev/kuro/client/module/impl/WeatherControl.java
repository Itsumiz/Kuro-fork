package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.util.Mth;

/**
 * Weather Control: pick the weather you see, whatever the server says. Clear sky, rain or a thunderstorm, with a strength
 * slider. Purely client-side: the server's weather does not change, other players see their own. In cold biomes
 * "rain" falls as snow, like in the real game. When you turn the module off the weather you had is put back.
 */
public class WeatherControl extends Module {
    private static final String[] MODES = {"Clear", "Rain", "Thunder"};

    private final Setting.Num mode = add(new Setting.Num("Weather", 0, 0, MODES.length - 1, 1).labelled(MODES));
    private final Setting.Num strength = add(new Setting.Num("Rain strength %", 100, 10, 100, 5));

    private float savedRain, savedThunder;
    private boolean saved;

    public WeatherControl() { super("Weather Control", Category.RENDER); }

    @Override
    protected void onEnable() {
        saved = false;
        remember();
    }

    @Override
    protected void onDisable() {
        if (saved && mc.level != null) {
            mc.level.setRainLevel(savedRain);
            mc.level.setThunderLevel(savedThunder);
        }
        saved = false;
    }

    private void remember() {
        if (saved || mc.level == null) return;
        savedRain = mc.level.getRainLevel(1.0f);
        savedThunder = mc.level.getThunderLevel(1.0f);
        saved = true;
    }

    @Override
    public void onTick() {
        if (mc.level == null) { saved = false; return; }
        remember();
        int m = mode.intValue();
        float rain = m == 0 ? 0f : (float) (strength.value / 100.0);
        float thunder = m == 2 ? rain : 0f;
        mc.level.setRainLevel(Mth.clamp(rain, 0f, 1f));            // sets the previous value too, so there is no slow fade the server could fight
        mc.level.setThunderLevel(Mth.clamp(thunder, 0f, 1f));
    }
}
