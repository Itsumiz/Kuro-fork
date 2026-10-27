package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.Freeze;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * sendPacket is declared on ClientCommonNetworkHandler (the parent of ClientPlayNetworkHandler since 1.20.2),
 * so it cannot be targeted from a mixin on ClientPlayNetworkHandler: Mixin only looks in the exact target class.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonNetworkHandlerMixin {
    /**
     * Server transaction (ping). TAIL = game thread only (the network-thread pass is thrown away before the end).
     * The pong has already been queued by then; ServerSync only needs to know a transaction cycle happened.
     */
    @Inject(method = "handlePing", at = @At("TAIL"), require = 0)
    private void kuro$transaction(net.minecraft.network.protocol.common.ClientboundPingPacket packet, CallbackInfo ci) {
        dev.kuro.client.util.ServerSync.onTransaction();
    }

    /** Freeze: movement packets never leave the client, so the server keeps you where you froze. */
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void kuro$freeze(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundMovePlayerPacket && Freeze.holding()) ci.cancel();
    }
}
