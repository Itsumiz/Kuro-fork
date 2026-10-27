package dev.kuro.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.kuro.client.echo.event.EventMove;
import dev.kuro.client.echo.handlers.RotationHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Movement packets always use the real camera yaw/pitch so WASD and Grim's
 * movement simulation stay vanilla. Silent aim angles are sent separately as
 * pure Look packets via {@link dev.kuro.client.util.Rotations#sendLookPacket()}.
 */
@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin {

    // After a forced silent look packet the server still holds the silent angles. Vanilla only puts the rotation into a
    // movement packet when it changed, so nudge the real yaw by a hair (0.03 deg, invisible) to make this tick's
    // movement packet carry the real camera angles again.
    /** Pre-motion: runs before anything of this tick's movement packet is built (after vanilla input handling). */
    @Inject(method = "sendPosition", at = @At("HEAD"), require = 0)
    private void kuro$restoreLook(CallbackInfo ci) {
        // pre-motion modules act first (their packets go out ahead of the movement packet), then the look restore
        if (dev.kuro.client.KuroClient.modules != null) dev.kuro.client.KuroClient.modules.onPreMotion();
        if (dev.kuro.client.util.Rotations.consumeRestore()) {
            net.minecraft.client.player.LocalPlayer self = (net.minecraft.client.player.LocalPlayer) (Object) this;
            self.setYRot(self.getYRot() + 0.03f);
        }
    }

    @Inject(method = "sendPosition", at = @At("RETURN"), require = 0)
    private void kuro$markMoved(CallbackInfo ci) {
        dev.kuro.client.util.TickPhase.markMoved();
        // Post-motion: directly behind the movement packet
        if (dev.kuro.client.KuroClient.modules != null) dev.kuro.client.KuroClient.modules.onPostMotion();
    }

    // ---- echo client hooks (Anchor Aura): the movement packet carries the yaw / pitch an EventMove.Pre listener sets ----
    @Unique
    private EventMove kuro$echoMove;

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void kuro$echoMovePre(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        float pitch = RotationHandler.getEnforcedPitch(self.getXRot());
        // keep the server rotation in sync every tick (continuity, prevents AimModulo360)
        if (!RotationHandler.isHasSilentRotation()) RotationHandler.updateServerRotations(self.getYRot(), pitch);
        EventMove event = new EventMove.Pre(self.getX(), self.getY(), self.getZ(), self.onGround(), self.getYRot(), pitch);
        event.call();
        this.kuro$echoMove = event;
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void kuro$echoMovePost(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        new EventMove.Post(self.getX(), self.getY(), self.getZ(), self.onGround(), self.getYRot(), self.getXRot()).call();
        this.kuro$echoMove = null;
    }

    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private float kuro$echoYaw(float original) {
        return this.kuro$echoMove != null ? (float) this.kuro$echoMove.getYaw() : original;
    }

    @ModifyExpressionValue(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
    private float kuro$echoPitch(float original) {
        float pitch = this.kuro$echoMove != null ? (float) this.kuro$echoMove.getPitch() : original;
        return RotationHandler.getEnforcedPitch(pitch);
    }
}
