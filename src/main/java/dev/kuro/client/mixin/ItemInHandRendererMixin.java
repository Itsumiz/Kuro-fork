package dev.kuro.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kuro.client.knife.KnifeRenderer;
import dev.kuro.client.module.impl.KnifeModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Knife module hook. renderItem is called by vanilla after it has applied the equip / swing / use transforms, with the
 * pose at the item origin, which is the frame the knife's fist/elbow numbers are written in. If the knife draws, vanilla's
 * sword model is skipped.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Inject(method = "renderItem", at = @At("HEAD"), cancellable = true)
    private void kuro$knife(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack pose,
                            SubmitNodeCollector collector, int light, CallbackInfo ci) {
        KnifeModule knife = KnifeModule.get();
        if (knife == null || !knife.isEnabled()) return;
        if (KnifeRenderer.render(stack, context, pose, collector, light, knife.size(), knife.matchMaterial(),
                knife.offX(), knife.offY(), knife.offZ(), knife.knife(), knife.trailSeconds())) ci.cancel();
    }

    /**
     * "Attack animation" off: vanilla feeds the swing progress into the hand transform, so zeroing it for the held knife
     * keeps the hand and knife still when you hit. Only the swing is touched; equip, inspect and sway are not.
     * require = 0: if this method's signature differs in your mappings the toggle just does nothing instead of crashing.
     */
    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
    private float kuro$noAttackSwing(float swingProgress) {
        KnifeModule knife = KnifeModule.get();
        if (knife == null || !knife.isEnabled() || knife.attackAnimation()) return swingProgress;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !KnifeRenderer.isKnifeItem(mc.player.getMainHandItem())) return swingProgress;
        return 0.0f;
    }
}
