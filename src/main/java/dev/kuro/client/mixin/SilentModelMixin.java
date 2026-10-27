package dev.kuro.client.mixin;

import dev.kuro.client.util.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third person (F5): while a module with silent angles asks for it (Silent Aura, "Show in F5"), your own player model turns
 * its body and head to those angles, so you see the player look at the target. The first-person view is not changed.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class SilentModelMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void kuro$silentModel(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player || !Rotations.showOnModel()) return;
        state.bodyRot = Rotations.yaw();
        state.yRot = 0f;                       // head relative to the body: straight ahead
        state.xRot = Rotations.pitch();
    }
}
