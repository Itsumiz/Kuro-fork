package dev.kuro.client.mixin;

import dev.kuro.client.echo.event.MouseUpdateEvent;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fires the echo MouseUpdateEvent right before the mouse movement of this frame is applied to the player's rotation. */
@Mixin(MouseHandler.class)
public class EchoMouseHandlerMixin {

    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void kuro$mouseUpdate(CallbackInfo ci) {
        MouseUpdateEvent event = new MouseUpdateEvent(accumulatedDX, accumulatedDY);
        event.call();
        if (event.cancelled) ci.cancel();
    }
}
