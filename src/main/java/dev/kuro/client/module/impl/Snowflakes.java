package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.ShaderUi;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Snow drifting down the screen while the client menu is open. Purely cosmetic. */
public class Snowflakes extends Module {
    private static Snowflakes instance;

    private final Setting.Num amount = add(new Setting.Num("Amount", 150, 20, 500, 10));
    private final Setting.Num speed = add(new Setting.Num("Speed", 1.0, 0.3, 3.0, 0.1));
    private final Setting.Bool big = add(new Setting.Bool("Big flakes", true));

    private static final class Flake { double x, y, vy, phase, drift; int size; int alpha; }

    private final List<Flake> flakes = new ArrayList<>();
    private final Random rng = new Random();
    private long last;

    public Snowflakes() { super("Snowflakes", Category.RENDER); instance = this; }

    /** Called by the menu screen every frame it draws. */
    public static void draw(GuiGraphics g, int w, int h) {
        if (instance != null && instance.isEnabled() && w > 0 && h > 0) instance.render(g, w, h);
    }

    private Flake spawn(int w, int h, boolean anywhere) {
        Flake f = new Flake();
        f.x = rng.nextDouble() * w;
        f.y = anywhere ? rng.nextDouble() * h : -rng.nextDouble() * 20;
        f.vy = 12 + rng.nextDouble() * 28;               // pixels per second
        f.phase = rng.nextDouble() * Math.PI * 2;
        f.drift = 6 + rng.nextDouble() * 14;
        f.size = big.value && rng.nextInt(6) == 0 ? 3 : rng.nextInt(3) == 0 ? 2 : 1;
        f.alpha = 110 + rng.nextInt(130);
        return f;
    }

    private void render(GuiGraphics g, int w, int h) {
        long now = System.nanoTime();
        double dt = last == 0 ? 0 : Math.min(0.1, (now - last) / 1e9);   // clamp so a pause doesn't teleport the snow
        last = now;

        int want = amount.intValue();
        while (flakes.size() < want) flakes.add(spawn(w, h, true));
        while (flakes.size() > want) flakes.remove(flakes.size() - 1);

        double t = now / 1e9;
        for (Flake f : flakes) {
            f.y += f.vy * speed.value * dt;
            f.x += Math.sin(t * 0.9 + f.phase) * f.drift * dt;
            if (f.y > h + 4 || f.x < -6 || f.x > w + 6) {
                Flake n = spawn(w, h, false);
                f.x = n.x; f.y = n.y; f.vy = n.vy; f.phase = n.phase; f.drift = n.drift; f.size = n.size; f.alpha = n.alpha;
            }
            int x = (int) f.x, y = (int) f.y, c = (f.alpha << 24) | 0xFFFFFF;
            int s = f.size + 1;                          // soft round flakes drawn by the shape shader
            ShaderUi.shape(g, ShaderUi.Mode.FLAT, x, y, x + s, y + s, s / 2, c);
        }
    }
}
