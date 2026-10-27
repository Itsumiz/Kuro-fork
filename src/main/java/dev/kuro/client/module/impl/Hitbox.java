package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Hitboxes: a filled, shaded box with an outline around players (optionally mobs and end crystals), drawn through
 * walls. Uses the same world-space renderer as Block ESP (WorldGfx, one GPU call), so the boxes follow the entities
 * smoothly and cost almost nothing. Friends get the Friends colour.
 * Colour is fully customizable with a colour wheel (hue + saturation) and a brightness bar; the outline can use its own colour.
 * Defaults give a light blue.
 */
public class Hitbox extends Module {
    private final Setting.Bool players = add(new Setting.Bool("Players", true));
    private final Setting.Bool mobs = add(new Setting.Bool("Mobs", false));
    private final Setting.Bool crystals = add(new Setting.Bool("End crystals", false));
    private final Setting.Num range = add(new Setting.Num("Range (blocks)", 64, 8, 200, 8));
    private final Setting.Num fillOpacity = add(new Setting.Num("Fill opacity", 45, 10, 120, 5));
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 1, 1, 3, 1));
    private final Setting.Bool showExpanded = add(new Setting.Bool("Show expanded box", true));   // draw the Hitbox Expand size
    private final Setting.Bool smooth = add(new Setting.Bool("Smooth motion", false));   // off = exact box the game aims at (moves 20x/s); on = slides between ticks
    private final Setting.Bool glow = add(new Setting.Bool("Glow", false));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.55f).wheel(0.6f, 1f));   // colour wheel: pick hue + saturation, brightness bar
    private final Setting.Bool ownOutline = add(new Setting.Bool("Separate outline colour", false));
    private final Setting.Hue outlineColor = add(new Setting.Hue("Outline color", 0.55f).wheel(0.4f, 1f));
    private final Setting.Bool friendColor = add(new Setting.Bool("Friend colour", true));
    private final Setting.Bool invisible = add(new Setting.Bool("Invisible", true));

    private static final int MAX_BOXES = 200;

    public Hitbox() { super("Hitboxes", Category.RENDER); }

    @Override
    public void onWorld() {
        if (mc.level == null || mc.player == null) return;
        Vec3 cam = Projector.camPos;
        double max2 = range.value * range.value;
        int t = thickness.intValue(), a = fillOpacity.intValue(), drawn = 0;
        boolean self = Freecam.active();
        int custom = color.rgb();
        int edge = ownOutline.value ? outlineColor.rgb() : -1;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (drawn >= MAX_BOXES) break;
            if (e == mc.player && !self) continue;
            if (e.isRemoved()) continue;
            if (e instanceof Player p) {
                if (!players.value || p.isSpectator()) continue;
            } else if (e instanceof EndCrystal) {
                if (!crystals.value) continue;
            } else if (e instanceof LivingEntity && !(e instanceof ArmorStand)) {
                if (!mobs.value) continue;
            } else continue;
            if (!invisible.value && e.isInvisible()) continue;

            // The box the game really aims at: the entity's own bounding box (at its last tick position) grown by its
            // targeting margin, which is where Hitbox Expand adds its size. "Smooth motion" slides it between ticks.
            AABB bb = e.getBoundingBox();
            AABB ghost = Backtrack.ghostBox(e);   // Backtrack (Ghost hitbox mode): the box lags behind the player
            if (ghost != null) bb = ghost;
            else if (smooth.value) {
                Vec3 lp = e.getPosition(Projector.tickProgress);
                bb = bb.move(lp.x - e.getX(), lp.y - e.getY(), lp.z - e.getZ());
            }
            double m = showExpanded.value ? e.getPickRadius() : 0;
            if (m > 0) bb = bb.inflate(m);
            double cx = (bb.minX + bb.maxX) / 2, cy = (bb.minY + bb.maxY) / 2, cz = (bb.minZ + bb.maxZ) / 2;
            double dx = cx - cam.x, dy = cy - cam.y, dz = cz - cam.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            if (Projector.depth(cx, cy, cz) < -1.0) continue;   // behind us

            boolean friend = friendColor.value && Friends.is(e);
            int rgb = (friend ? Friends.argb() : 0xFF000000 | custom) & 0xFFFFFF;
            int line = friend || edge < 0 ? rgb : edge;
            WorldGfx.shadedBox(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ,
                    rgb, a, 0xFF000000 | line, t, glow.value && drawn < 25);
            drawn++;
        }
    }
}
