package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.ForcedKeys;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionResult;

import java.util.concurrent.ThreadLocalRandom;

/**
 * WTap / STap: resets your sprint after a hit so the next hit knocks back again.
 *
 * Vanilla pipeline: this module sends NO packets. It only changes what the game reads as the forward / back key,
 * once per tick, at the very start of the tick (before the game handles input). The game then does everything
 * itself: sprint drops, its own sprint-stop / sprint-start commands and the movement packet go out in the normal
 * place, de-duplicated by the game (at most one sprint command per tick).
 *
 *  - A hit (yours or any module's: the callback sits on the attack itself) arms a tap. Nothing happens in that tick.
 *  - Next tick start: the forward key reads as released (W-tap), or the back key reads as pressed (S-tap) when
 *    Dynamic is on and the target is closer than S-Tap distance.
 *  - It lasts "Keyboard delay" ticks (random between min and max), then the keys read normally again.
 *  - A tap starts and ends in different ticks, so the key state never changes twice in one tick.
 *  - Sprint resumes the vanilla way (sprint key held / Sprint module), nothing is forced.
 *
 * Settings: Dynamic, Keyboard delay (ticks), Range, S-Tap distance, Only sprinting, Only ground.
 */
public class WTap extends Module {
    private final Setting.Bool dynamic = add(new Setting.Bool("Dynamic (S-tap when close)", true));
    private final Setting.Num delayMin = add(new Setting.Num("Keyboard delay min (ticks)", 1, 1, 10, 1));
    private final Setting.Num delayMax = add(new Setting.Num("Keyboard delay max (ticks)", 2, 1, 10, 1));
    private final Setting.Num rangeMin = add(new Setting.Num("Range min", 0.0, 0.0, 6.0, 0.1));
    private final Setting.Num rangeMax = add(new Setting.Num("Range max", 3.5, 0.0, 6.0, 0.1));
    private final Setting.Num sTapDistance = add(new Setting.Num("S-Tap distance", 2.7, 0.0, 6.0, 0.1));
    private final Setting.Bool onlySprinting = add(new Setting.Bool("Only sprinting", true));
    private final Setting.Bool onlyGround = add(new Setting.Bool("Only ground", false));

    private boolean armed;
    private double armDistance;
    private int ticksLeft;            // > 0 while a tap is running
    private KeyMapping tapKey;        // the key we are holding down / suppressing
    private boolean tapIsBack;

    public WTap() {
        super("WTap", Category.COMBAT);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClientSide() || player != mc.player || !isEnabled()) return InteractionResult.PASS;
            if (!(entity instanceof LivingEntity)) return InteractionResult.PASS;
            if (armed || ticksLeft > 0) return InteractionResult.PASS;           // one tap at a time
            if (onlySprinting.value && !player.isSprinting()) return InteractionResult.PASS;
            if (onlyGround.value && !player.onGround()) return InteractionResult.PASS;
            double d = player.distanceTo(entity);
            double lo = Math.min(rangeMin.value, rangeMax.value), hi = Math.max(rangeMin.value, rangeMax.value);
            if (d < lo || d > hi) return InteractionResult.PASS;
            armed = true;                                                   // acted on at the start of the next tick
            armDistance = d;
            return InteractionResult.PASS;
        });
    }

    /** Runs before Sprint and the other modules read the keys this tick. */
    @Override
    public boolean early() { return true; }

    @Override
    protected void onDisable() {
        stopTap();
        armed = false;
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null || mc.options == null
                || mc.screen != null || Freecam.active() || mc.player.isSpectator()) {
            stopTap();
            armed = false;
            return;
        }

        // A running tap: count down, end it in a later tick than it started
        if (ticksLeft > 0) {
            if (--ticksLeft == 0) stopTap();
            return;
        }

        if (!armed) return;
        armed = false;
        if (!mc.options.keyUp.isDown()) return;       // not walking forward: nothing to tap

        int lo = Math.min(delayMin.intValue(), delayMax.intValue());
        int hi = Math.max(delayMin.intValue(), delayMax.intValue());
        int len = lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);

        tapIsBack = dynamic.value && armDistance < sTapDistance.value;
        tapKey = tapIsBack ? mc.options.keyDown : mc.options.keyUp;
        if (tapIsBack) ForcedKeys.force(tapKey); else ForcedKeys.suppress(tapKey);
        ticksLeft = len;     // this tick counts as the first tap tick
    }

    private void stopTap() {
        if (tapKey != null) {
            if (tapIsBack) ForcedKeys.release(tapKey); else ForcedKeys.unsuppress(tapKey);
            tapKey = null;
        }
        ticksLeft = 0;
    }
}
