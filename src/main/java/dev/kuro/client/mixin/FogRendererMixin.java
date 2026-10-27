package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.SkyModule;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the fog / horizon colour match the Sky module so the world fades into the white (or gray) sky.
 * getFogColor is the one fog method with a single overload (applyFog has two with different return types).
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Inject(method = "computeFogColor", at = @At("RETURN"), cancellable = true)
    private void kuro$fog(Camera camera, float tickProgress, ClientLevel world, int viewDistance, float skyDarkness,
                           CallbackInfoReturnable<Vector4f> cir) {
        if (!SkyModule.active() || !SkyModule.get().fogOn()) return;
        int rgb = SkyModule.get().fogRgb();
        cir.setReturnValue(new Vector4f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1f));
    }
}
