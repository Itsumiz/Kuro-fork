package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plays a sound when you hit something. Purely audio feedback: nothing is sent to the server and nothing about the
 * attack itself is changed.
 *
 * Every attack goes through MultiPlayerGameMode.attack (clicks and modules alike); ClientPlayerInteractionManagerMixin
 * reports it to {@link #onAttack}. With "Only when the hit lands" the sound waits until the target's hurt timer jumps
 * (the server accepted the damage), so hits that are swallowed by invulnerability frames, blocked or out of reach stay
 * silent. A different chime plays when the hit also kills.
 */
public class HitSounds extends Module {
    private static final String[] NAMES = {"Skeet", "Pop", "Click", "Bell", "Orb"};
    private static final SoundEvent[] SOUNDS = {
            event("hit_skeet"), event("hit_pop"), event("hit_click"), event("hit_bell"), SoundEvents.EXPERIENCE_ORB_PICKUP};
    private static final SoundEvent KILL = event("hit_kill");
    /** How long after the swing the server may still answer (ticks). Covers high ping without catching unrelated damage. */
    private static final int WINDOW_TICKS = 10;
    private static final int MAX_PENDING = 8;
    private static HitSounds instance;

    private final Setting.Num sound = add(new Setting.Num("Sound", 1, 1, NAMES.length, 1).labelled(NAMES));
    private final Setting.Num volume = add(new Setting.Num("Volume", 0.8, 0.1, 1.0, 0.05));
    private final Setting.Num pitch = add(new Setting.Num("Pitch", 1.0, 0.5, 2.0, 0.05));
    private final Setting.Bool variation = add(new Setting.Bool("Pitch variation", true));
    private final Setting.Bool confirm = add(new Setting.Bool("Only when the hit lands", true));
    private final Setting.Bool killSound = add(new Setting.Bool("Kill sound", true));
    private final Setting.Bool playersOnly = add(new Setting.Bool("Players only", false));
    private final Setting.Button preview = add(new Setting.Button("Preview sound", () -> play(false)));

    private static final class Pending {
        final int id;
        int ticks = WINDOW_TICKS;
        int lastHurt;
        Pending(int id, int hurt) { this.id = id; this.lastHurt = hurt; }
    }

    private final List<Pending> pending = new ArrayList<>();

    public HitSounds() {
        super("Hit Sounds", Category.COMBAT);
        instance = this;
    }

    private static SoundEvent event(String name) {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("kuro", name));
    }

    /** Called by ClientPlayerInteractionManagerMixin at the start of every attack on {@code target}. */
    public static void onAttack(Entity target) {
        HitSounds m = instance;
        if (m != null && m.isEnabled()) m.attacked(target);
    }

    private void attacked(Entity e) {
        if (mc.player == null || e == mc.player || !(e instanceof LivingEntity le) || le.isDeadOrDying()) return;
        if (playersOnly.value && !(e instanceof Player)) return;
        if (!confirm.value) {                       // "on click": instant, no confirmation
            play(false);
            return;
        }
        for (Pending p : pending) {                 // swung at it again: keep waiting, just extend the window
            if (p.id == le.getId()) { p.ticks = WINDOW_TICKS; return; }
        }
        if (pending.size() >= MAX_PENDING) pending.remove(0);
        pending.add(new Pending(le.getId(), le.hurtTime));
    }

    @Override
    public void onTick() {
        if (pending.isEmpty()) return;
        if (mc.level == null) { pending.clear(); return; }
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            Entity e = mc.level.getEntity(p.id);
            if (!(e instanceof LivingEntity le) || le.isRemoved() && !le.isDeadOrDying()) { it.remove(); continue; }
            boolean landed = le.hurtTime > p.lastHurt;          // the hurt timer was reset by new damage
            boolean dead = le.isDeadOrDying();
            p.lastHurt = le.hurtTime;
            if (landed || dead) {
                play(dead);
                it.remove();
            } else if (--p.ticks <= 0) {
                it.remove();
            }
        }
    }

    @Override protected void onDisable() { pending.clear(); }

    private void play(boolean kill) {
        if (mc.player == null) return;
        SoundEvent ev = kill && killSound.value ? KILL : SOUNDS[Math.max(0, Math.min(SOUNDS.length - 1, sound.intValue() - 1))];
        float p = (float) pitch.value;
        if (variation.value) p *= 0.94f + ThreadLocalRandom.current().nextFloat() * 0.12f;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(ev, p, (float) volume.value));
    }
}
