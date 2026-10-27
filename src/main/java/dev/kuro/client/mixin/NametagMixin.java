package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.Nametags;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hides the vanilla floating player name while the Nametags module draws its own. */
@Mixin(LivingEntityRenderer.class)
public abstract class NametagMixin {
    // require = 0: if this signature differs in your mappings the tags just show twice instead of crashing on startup
    @Inject(method = "shouldShowName", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$hideVanillaTag(LivingEntity entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
        if (Nametags.hidesVanilla(entity)) cir.setReturnValue(false);
    }
}
