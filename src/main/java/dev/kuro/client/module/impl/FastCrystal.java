package dev.kuro.client.module.impl;

import dev.kuro.client.mixin.MinecraftClientAccessor;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Fast crystal:
 *  - Fast place: removes the 4-tick delay between right-clicks while you hold an end crystal.
 *  - Instant break: hits a crystal the moment it appears (spawn packet), not a tick later, as long as your
 *    crosshair is on it (or any crystal in range with "Only when aimed at" off). A per-tick scan catches the rest.
 * Safety: each crystal is hit once and only retried after "Retry hit after", total hits are capped per second, and the
 * spawn hook swallows errors. If Auto Dtap is also on, its extra taps are added to every hit (more packets): use one or the other.
 * The break range never exceeds your real entity interaction range (so Reach extends it), because the server
 * rejects hits from further away.
 */
public class FastCrystal extends Module {
    private static FastCrystal instance;
    private final Setting.Bool fastPlace = add(new Setting.Bool("Fast place", true));
    private final Setting.Bool instantBreak = add(new Setting.Bool("Instant break", true));
    private final Setting.Bool aimedOnly = add(new Setting.Bool("Only when aimed at", true));
    private final Setting.Num range = add(new Setting.Num("Break range", 4.0, 1.0, 6.0, 0.5));
    private final Setting.Num placeDelay = add(new Setting.Num("Place delay (ticks)", 0, 0, 4, 1));      // vanilla is 4
    private final Setting.Num retry = add(new Setting.Num("Retry hit after (ticks)", 6, 2, 20, 1));     // a crystal that is still there after this long is hit again
    private final Setting.Num maxHits = add(new Setting.Num("Max hits per second", 20, 5, 40, 1));      // packet cap: too many packets gets you kicked or ignored
    private final Map<Integer, Integer> hitAt = new HashMap<>();   // crystal id -> tick we hit it (no double hits)
    private int tick;
    private double budget;                                         // hits we may still send (refills every tick)

    public FastCrystal() { super("Fast Crystal", Category.COMBAT); instance = this; }

    @Override protected void onEnable() { hitAt.clear(); budget = 1; }
    @Override protected void onDisable() { hitAt.clear(); }

    @Override
    public void onPreTick() {
        tick++;
        budget = Math.min(Math.max(2.0, maxHits.value / 20.0 * 2), budget + maxHits.value / 20.0);   // refill, allow a small burst
        if (tick % 40 == 0) hitAt.values().removeIf(t -> tick - t > 60);                              // forget old crystals
        if (!fastPlace.value || mc.player == null || mc.screen != null) return;
        if (mc.player.getMainHandItem().is(Items.END_CRYSTAL) || mc.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            var acc = (MinecraftClientAccessor) mc;
            if (acc.kuro$getItemUseCooldown() > placeDelay.intValue()) acc.kuro$setItemUseCooldown(placeDelay.intValue());
        }
    }

    @Override
    public void onTick() {
        if (!instantBreak.value || !ready()) return;
        double r = reach();
        AABB area = mc.player.getBoundingBox().inflate(r + 1.0);
        for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, area, Entity::isAlive)) tryBreak(c);
    }

    /** Called from the entity-spawn packet handler (game thread), so a freshly placed crystal is hit in the same tick. */
    public static void onSpawn(Entity e) {
        // never let an exception escape into the game's packet handler
        try {
            if (instance != null && instance.isEnabled() && instance.instantBreak.value && e instanceof EndCrystal c && instance.ready())
                instance.tryBreak(c);
        } catch (Throwable ignored) {}
    }

    private boolean ready() {
        return mc.player != null && mc.level != null && mc.gameMode != null && mc.screen == null
                && !mc.player.isSpectator() && !Freecam.active();
    }

    private double reach() { return Math.min(range.value, mc.player.entityInteractionRange()); }

    private void tryBreak(EndCrystal c) {
        if (!c.isAlive()) return;
        Integer last = hitAt.get(c.getId());
        if (last != null && tick - last < retry.intValue()) return;   // already hit; wait for the server to remove it
        if (budget < 1.0) return;                                      // packet cap reached this tick
        double r = reach();
        Vec3 eye = mc.player.getEyePosition();
        if (aimedOnly.value) {
            Vec3 end = eye.add(mc.player.getViewVector(1.0f).scale(r));
            if (c.getBoundingBox().inflate(c.getPickRadius()).clip(eye, end).isEmpty()) return;
        } else {
            AABB b = c.getBoundingBox();
            double dx = Math.max(Math.max(b.minX - eye.x, 0), eye.x - b.maxX);
            double dy = Math.max(Math.max(b.minY - eye.y, 0), eye.y - b.maxY);
            double dz = Math.max(Math.max(b.minZ - eye.z, 0), eye.z - b.maxZ);
            if (dx * dx + dy * dy + dz * dz > r * r) return;   // nearest point of the crystal is out of reach
        }
        hitAt.put(c.getId(), tick);
        budget -= 1.0;
        AttackPackets.hit(c);
    }
}
