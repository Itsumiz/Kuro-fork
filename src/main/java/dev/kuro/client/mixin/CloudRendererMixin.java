package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.SkyModule;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Cloud colour (white by default) for the Sky module: renderClouds(int color, ...) gets the white colour (its alpha is kept, e.g. for rain). */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private int kuro$clouds(int color) {
        if (!SkyModule.active() || !SkyModule.get().cloudsOn()) return color;
        return (color & 0xFF000000) | SkyModule.get().cloudRgb();
    }
}
