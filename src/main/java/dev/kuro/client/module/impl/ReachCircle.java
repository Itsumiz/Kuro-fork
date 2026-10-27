package dev.kuro.client.module.impl;

import dev.kuro.client.gui.UiThemes;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Reach Circle: a glowing circle on the ground around every player's feet showing how far they can hit. It uses the same
 * ring look as Jump Bubbles, only bigger and it stays. The radius is the attack reach (3.0 blocks in survival) plus half
 * your own hitbox, because a hit lands when their reach touches any part of YOU. The circle turns the "In reach" colour
 * while you are standing inside it. It follows the player and is drawn on the floor below them, not in mid air.
 */
public class ReachCircle extends Module {
    private final Setting.Bool followTheme = add(new Setting.Bool("Follow theme", true));   // on = the circle uses the UI theme's accent instead of Color
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.83f).wheel());
    private final Setting.Hue dangerColor = add(new Setting.Hue("In reach color", 0.0f).wheel());
    private final Setting.Num reach = add(new Setting.Num("Reach (blocks)", 3.0, 2.0, 6.0, 0.1));
    private final Setting.Bool addHitbox = add(new Setting.Bool("Add your hitbox", true));
    private final Setting.Num range = add(new Setting.Num("Range", 40, 8, 100, 4));
    private final Setting.Bool bubble = add(new Setting.Bool("Filled bubble", true));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool pulse = add(new Setting.Bool("Pulse", true));
    private final Setting.Bool friends = add(new Setting.Bool("Friends", false));
    private final Setting.Bool self = add(new Setting.Bool("Include yourself", false));

    public ReachCircle() { super("Reach Circle", Category.RENDER); }

    @Override
    public void onWorld() {
        if (mc.level == null || mc.player == null) return;
        float pt = Projector.tickProgress;
        double max2 = range.value * range.value;
        double radius = reach.value + (addHitbox.value ? 0.3 : 0.0);
        double wave = pulse.value ? 0.5 + 0.5 * Math.sin((mc.level.getGameTime() + pt) * 0.15) : 0.5;
        Vec3 me = mc.player.getPosition(pt);
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player && !self.value) continue;
            if (p.isSpectator() || !p.isAlive() || AntiBot.isBot(p)) continue;
            boolean friend = Friends.is(p);
            if (friend && !friends.value) continue;
            if (p.distanceToSqr(mc.player) > max2) continue;
            Vec3 pos = p.getPosition(pt);
            double y = groundY(pos) + 0.02;
            boolean inReach = p != mc.player && Math.hypot(me.x - pos.x, me.z - pos.z) <= radius && Math.abs(me.y - pos.y) < 3.0;
            int rgb = inReach ? dangerColor.rgb() : followTheme.value ? (UiThemes.accent() & 0xFFFFFF) : color.rgb();
            int a = (int) (0xE0 * (0.75 + 0.25 * wave));
            if (bubble.value) WorldGfx.ring(pos.x, y, pos.z, 0.0, radius, (0x1C << 24) | rgb, 48);
            double w = 0.05;
            if (glow.value) WorldGfx.ring(pos.x, y, pos.z, radius - w * 3.5, radius + w * 3.5, (0x30 << 24) | rgb, 64);
            WorldGfx.ring(pos.x, y, pos.z, radius - w, radius + w, (a << 24) | brighten(rgb), 64);
        }
    }

    /** Height of the floor under a player's feet (up to 6 blocks down); their own feet when there is none. */
    private double groundY(Vec3 feet) {
        Vec3 from = feet.add(0, 0.3, 0), to = feet.add(0, -6.0, 0);
        BlockHitResult r = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return r.getType() == HitResult.Type.MISS ? feet.y : r.getLocation().y;
    }

    private static int brighten(int rgb) {
        int r = (int) (((rgb >> 16) & 0xFF) * 0.45 + 255 * 0.55), g = (int) (((rgb >> 8) & 0xFF) * 0.45 + 255 * 0.55), b = (int) ((rgb & 0xFF) * 0.45 + 255 * 0.55);
        return (r << 16) | (g << 8) | b;
    }
}
