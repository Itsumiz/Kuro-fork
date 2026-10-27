package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Ballistics;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Bow Aim: shows where the arrow will land. While you draw a bow (or hold a loaded crossbow) the exact flight path
 * is simulated every frame from your real eye position, view direction, the draw power and your own movement
 * (arrows inherit it), and drawn as a line with a marker box where it hits a block. A player or mob the path
 * crosses is boxed and named. Arrows: gravity 0.05, drag 0.99. Bow speed = 3.0 x draw power, crossbow bolt = 3.15.
 * Nothing is sent to the server; this only draws.
 */
public class BowAim extends Module {
    private final Setting.Bool crossbows = add(new Setting.Bool("Crossbows", true));
    private final Setting.Bool preview = add(new Setting.Bool("Show bow when not drawing", false));   // full-draw preview while a bow is held
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.55f).wheel());
    private final Setting.Hue hitColor = add(new Setting.Hue("Hit color", 0.0f).wheel());
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 2, 1, 3, 1));
    private final Setting.Bool landing = add(new Setting.Bool("Landing marker", true));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool hitEntities = add(new Setting.Bool("Detect entities", true));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", true));
    private final Setting.Num maxTicks = add(new Setting.Num("Max flight (ticks)", 100, 20, 200, 10));

    private static final double GRAVITY = 0.05, DRAG = 0.99;

    private Ballistics.Result result;
    private final double[] seg = new double[4];
    private final double[] pt = new double[2];

    public BowAim() { super("Bow Aim", Category.RENDER); }

    @Override protected void onDisable() { result = null; }

    /** Launch speed for what you are shooting, or 0 for "nothing to show". */
    private double launchSpeed() {
        Player p = mc.player;
        if (p.isUsingItem()) {
            ItemStack use = p.getUseItem();
            if (use.getItem() instanceof BowItem) {
                float f = p.getTicksUsingItem() / 20.0f;
                f = (f * f + f * 2.0f) / 3.0f;
                f = Math.min(f, 1.0f);
                return f < 0.1f ? 0.0 : f * 3.0;     // vanilla: a draw under 0.1 does not fire
            }
            return 0.0;
        }
        if (crossbows.value) {
            ItemStack s = loadedCrossbow(p);
            if (s != null) return s.getItem() instanceof CrossbowItem ? 3.15 : 0.0;
        }
        if (preview.value && (p.getMainHandItem().getItem() instanceof BowItem || p.getOffhandItem().getItem() instanceof BowItem)) return 3.0;
        return 0.0;
    }

    /** The loaded crossbow in either hand that holds an arrow (a firework rocket flies differently, so it is skipped). */
    private static ItemStack loadedCrossbow(Player p) {
        for (ItemStack s : new ItemStack[]{p.getMainHandItem(), p.getOffhandItem()}) {
            if (!CrossbowItem.isCharged(s)) continue;
            var charged = s.get(DataComponents.CHARGED_PROJECTILES);
            if (charged != null && charged.contains(Items.FIREWORK_ROCKET)) continue;
            return s;
        }
        return null;
    }

    private void update() {
        result = null;
        Player p = mc.player;
        if (p == null || mc.level == null || p.isSpectator()) return;
        double speed = launchSpeed();
        if (speed <= 0.0) return;
        Vec3 look = p.getViewVector(Projector.tickProgress).normalize();
        Vec3 inherited = p.getDeltaMovement();
        Vec3 vel = new Vec3(look.x * speed + inherited.x, look.y * speed + (p.onGround() ? 0.0 : inherited.y), look.z * speed + inherited.z);
        Vec3 start = p.getEyePosition(Projector.tickProgress).subtract(0, 0.1, 0);   // arrows spawn 0.1 below the eyes
        result = Ballistics.simulate(p, start, vel, GRAVITY, DRAG, maxTicks.intValue(), hitEntities.value);
    }

    @Override
    public void onWorld() {
        update();
        Ballistics.Result r = result;
        if (r == null) return;
        int t = thickness.intValue();
        if (r.hit != null) {
            AABB b = r.hit.getBoundingBox();
            int rgb = 0xFF000000 | hitColor.rgb();
            WorldGfx.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, rgb, (0x40 << 24) | hitColor.rgb(), t, glow.value);
        }
        if (landing.value && r.end != null) {
            int rgb = r.hit != null ? hitColor.rgb() : color.rgb();
            Vec3 e = r.end;
            double s = 0.18;
            WorldGfx.box(e.x - s, e.y - s, e.z - s, e.x + s, e.y + s, e.z + s, 0xFF000000 | rgb, (0x60 << 24) | rgb, t, glow.value);
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        Ballistics.Result r = result;
        if (r == null || mc.screen != null) return;
        int t = thickness.intValue();
        int line = Look.withAlpha(color.rgb(), 0xDC), hitLine = Look.withAlpha(hitColor.rgb(), 0xDC);
        int endColor = r.hit != null ? hitLine : line;
        for (int i = 1; i < r.pts.size(); i++) {
            Vec3 a = r.pts.get(i - 1), b = r.pts.get(i);
            if (!Projector.segment(a.x, a.y, a.z, b.x, b.y, b.z, seg)) continue;
            // the last few ticks before the impact take the hit colour so the end of the path stands out
            Gfx.line(g, seg[0], seg[1], seg[2], seg[3], t, i >= r.pts.size() - 2 ? endColor : line);
        }
        if (!labels.value || r.end == null) return;
        Vec3 e = r.end;
        if (!Projector.project(e.x, e.y + 0.4, e.z, pt)) return;
        double dist = e.distanceTo(mc.player.getEyePosition(Projector.tickProgress));
        String text;
        if (r.hit != null) {
            String name = r.hit.getName().getString();
            text = (r.hit instanceof Player && Friends.is(r.hit) ? "Friend " : "Hit ") + name + "  " + Math.round(dist) + "m";
        } else {
            text = Math.round(dist) + "m  " + r.ticks + "t";
        }
        Look.tagClamped(g, mc.font, text, (int) pt[0], (int) pt[1] - 14, endColor);
    }
}
