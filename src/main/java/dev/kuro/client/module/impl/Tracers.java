package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;

/** Draws a line from the crosshair (screen centre) to every player the client has loaded. */
public class Tracers extends Module {
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.15f).wheel());
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 1, 1, 3, 1));
    private final Setting.Bool body = add(new Setting.Bool("Aim at body", true));   // off = feet
    private final Setting.Bool invisible = add(new Setting.Bool("Invisible", true));
    private final double[] pt = new double[2];

    public Tracers() { super("Tracers", Category.RENDER); }

    @Override
    public void onHud(GuiGraphics g) {
        boolean cam = Freecam.active();
        int line = Look.withAlpha(color.rgb(), 0xC8), t = thickness.intValue();
        double cx = Projector.width() / 2.0, cy = Projector.height() / 2.0;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player && !cam) continue;
            if (p.isSpectator() || (!invisible.value && p.isInvisible())) continue;
            Vec3 pos = p.getPosition(Projector.tickProgress);
            double y = pos.y + (body.value ? p.getBbHeight() * 0.5 : 0.0);
            Projector.projectOrPoint(pos.x, y, pos.z, pt);   // off-screen / behind targets still point the right way
            Gfx.line(g, cx, cy, pt[0], pt[1], t, dev.kuro.client.util.Friends.is(p) ? dev.kuro.client.util.Friends.argb() : line);
        }
    }
}
