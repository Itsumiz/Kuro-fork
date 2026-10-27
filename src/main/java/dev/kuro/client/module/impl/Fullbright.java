package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

public class Fullbright extends Module {
    private boolean applied;

    public Fullbright() { super("Fullbright", Category.RENDER); }

    @Override
    public void onTick() {
        LocalPlayer p = mc.player;
        if (p == null) return;
        MobEffectInstance cur = p.getEffect(MobEffects.NIGHT_VISION);
        if (cur == null || cur.getDuration() < 400) {
            // client-side only; long duration avoids the fade-out flicker
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1200, 0, true, false, false));
            applied = true;
        }
    }

    @Override
    protected void onDisable() {
        if (applied && mc.player != null) mc.player.removeEffect(MobEffects.NIGHT_VISION);
        applied = false;
    }
}
