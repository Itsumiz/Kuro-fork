package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.Zoom;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom: narrows the field of view the game renders with. Written for both a float and a double return type, because the
 * type of GameRenderer.getFov has changed between versions. If your version names it differently (require = 0, so the game
 * still starts), Zoom just does nothing: search the mappings for the method that returns the FOV and put its name here.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void kuro$zoomFov(CallbackInfoReturnable cir) {
        Zoom.frame();
        if (Zoom.factor() <= 1.0001f) return;
        Object v = cir.getReturnValue();
        if (v instanceof Float f) cir.setReturnValue(Zoom.fov(f));
        else if (v instanceof Double d) cir.setReturnValue((double) Zoom.fov(d.floatValue()));
    }
}
