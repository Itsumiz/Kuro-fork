package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;

/** Attacks the nearest valid target within range whenever the weapon is charged. */
public class KillAura extends Module {
    private final Setting.Num range = add(new Setting.Num("Range", 3.5, 1.0, 6.0, 0.1));
    private final Setting.Bool players = add(new Setting.Bool("Players", true));
    private final Setting.Bool mobs = add(new Setting.Bool("Other mobs", false));
    private final Setting.Num cooldown = add(new Setting.Num("Min charge", 0.95, 0.1, 1.0, 0.05));

    public KillAura() { super("Kill Aura", Category.COMBAT); }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.screen != null || Freecam.active() || mc.player.isUsingItem() || mc.player.isSpectator()) return;

        Entity best = null;
        double bestD = range.value;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!valid(e)) continue;
            double d = mc.player.distanceTo(e);
            if (d <= bestD) { bestD = d; best = e; }
        }
        if (best == null) return;

        AutoWeapon.onTarget(best);                              // Auto Weapon: right weapon for this target, the hit follows a tick later

        // charge of the item you hold; an axe hit on a shield / a mace smash does not wait for it (Auto Weapon decides)
        if (!AutoWeapon.bypass(best) && AutoWeapon.progress() < cooldown.value) return;
        if (!AutoWeapon.readyToAttack()) return;                // the slot was changed this tick: hit next tick

        AttackPackets.hit(best);                                // swing + attack; sent before the next movement packet
    }

    private boolean valid(Entity e) {
        if (e == mc.player || !e.isAlive()) return false;
        if (e instanceof Player p) return players.value && !p.isSpectator() && !dev.kuro.client.util.Friends.shield(p) && !AntiBot.isBot(p);
        return e instanceof LivingEntity && mobs.value;
    }
}
