package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.SkyModule;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.SkyRenderState;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sky colour override for the Sky module. In 1.21.11 the sky colour no longer comes from ClientWorld#getSkyColor:
 * SkyRendering#updateRenderState copies it (from the environment attributes) into SkyRenderState, which the sky
 * pass then draws, so that is where it is replaced.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRenderingMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void kuro$sky(ClientLevel world, float tickProgress, Camera camera, SkyRenderState state, CallbackInfo ci) {
        if (!SkyModule.active()) return;
        state.skyColor = 0xFF000000 | SkyModule.get().skyRgb();
        if (SkyModule.get().noGlow()) state.sunriseAndSunsetColor = 0;   // no orange sunrise / sunset band over the flat sky
        else if (SkyModule.get().customGlow() && state.sunriseAndSunsetColor != 0)
            state.sunriseAndSunsetColor = (state.sunriseAndSunsetColor & 0xFF000000) | SkyModule.get().glowRgb();   // recolour it, keep its fade
    }
}
