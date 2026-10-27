package dev.kuro.client.mixin;

import dev.kuro.client.echo.event.EventStartUseItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires the echo EventStartUseItem (Pre = cancellable) around Minecraft.startUseItem, the right-click handler. */
@Mixin(Minecraft.class)
public abstract class EchoMinecraftMixin {

    @Shadow public LocalPlayer player;
    @Shadow public HitResult hitResult;

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void kuro$useItemPre(CallbackInfo ci) {
        if (player == null) return;
        EventStartUseItem.Pre event = new EventStartUseItem.Pre(player, InteractionHand.MAIN_HAND, player.getMainHandItem(), hitResult);
        event.call();
        if (event.cancelled) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("RETURN"))
    private void kuro$useItemPost(CallbackInfo ci) {
        if (player == null) return;
        new EventStartUseItem.Post(player, InteractionHand.MAIN_HAND, player.getMainHandItem(), hitResult).call();
    }
}
