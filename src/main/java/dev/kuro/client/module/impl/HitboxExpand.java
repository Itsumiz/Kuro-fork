package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Friends;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;

/**
 * Hitbox expander: makes other entities' hitboxes bigger for YOUR aiming only (the crosshair / attack raycast), so you
 * can hit them when you are slightly off. It is applied in EntityMixin through Entity#getTargetingMargin, which the
 * game uses for entity picking only: nothing moves, collides or renders differently. The server still checks your
 * reach, so Size does not extend how FAR you can hit (see Reach), and very large sizes can be flagged by anti-cheat.
 * The Hitboxes module draws the expanded box while this is on.
 */
public class HitboxExpand extends Module {
    private static HitboxExpand instance;
    private final Setting.Num size = add(new Setting.Num("Size (blocks)", 0.3, 0.05, 1.0, 0.05));
    private final Setting.Bool players = add(new Setting.Bool("Players", true));
    private final Setting.Bool mobs = add(new Setting.Bool("Mobs", false));
    private final Setting.Bool crystals = add(new Setting.Bool("End crystals", true));
    private final Setting.Bool skipFriends = add(new Setting.Bool("Skip friends", true));

    public HitboxExpand() { super("Hitbox Expand", Category.COMBAT); instance = this; }

    /** Extra margin (blocks, on every side) for this entity's hitbox, or 0 when it should stay vanilla. */
    public static float margin(Entity e) {
        HitboxExpand h = instance;
        if (h == null || !h.isEnabled() || e == null || e == mc.player) return 0f;
        if (e instanceof Player p) {
            if (!h.players.value || p.isSpectator()) return 0f;
            if (h.skipFriends.value && Friends.is(e)) return 0f;
        } else if (e instanceof EndCrystal) {
            if (!h.crystals.value) return 0f;
        } else if (e instanceof LivingEntity && !(e instanceof ArmorStand)) {
            if (!h.mobs.value) return 0f;
        } else return 0f;
        return (float) h.size.value;
    }
}
