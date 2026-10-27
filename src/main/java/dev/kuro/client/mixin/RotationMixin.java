package dev.kuro.client.mixin;

import dev.kuro.client.util.RotationEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class RotationMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void kuro$onPlayerTickHead(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (Minecraft.getInstance().player != player) return;

        RotationEngine.RotationManager rm = RotationEngine.INSTANCE;
        if (rm.isControllerUpdating()) {
            rm.advanceAndApplyNow(1);
            rm.applyManagedRotation(true);
        }
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void kuro$onSendPositionPost(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (Minecraft.getInstance().player != player) return;
        RotationEngine.INSTANCE.applyManagedRotation(false);
    }

    @Mixin(ClientPacketListener.class)
    public static class PacketInterceptor {
        @ModifyVariable(method = "send", at = @At("HEAD"), argsOnly = true)
        private ServerboundMovePlayerPacket kuro$onSendPacket(ServerboundMovePlayerPacket packet) {
            if (Minecraft.getInstance().player == null) return packet;
            RotationEngine.RotationManager rm = RotationEngine.INSTANCE;

            if (rm.isControllerUpdating()) {
                float yaw = rm.getManagedYaw();
                float pitch = rm.getManagedPitch();
                RotationEngine.SENT.update(yaw, pitch, true);

                if (packet instanceof ServerboundMovePlayerPacket.PosRot p) {
                    // Pass false for horizontalCollision
                    return new ServerboundMovePlayerPacket.PosRot(p.getX(0), p.getY(0), p.getZ(0), yaw, pitch, p.isOnGround(), false);
                } else if (packet instanceof ServerboundMovePlayerPacket.Rot r) {
                    // Pass false for horizontalCollision
                    return new ServerboundMovePlayerPacket.Rot(yaw, pitch, r.isOnGround(), false);
                }
            } else {
                RotationEngine.SENT.update(packet.getYRot(0), packet.getXRot(0), false);
            }
            return packet;
        }
    }
}