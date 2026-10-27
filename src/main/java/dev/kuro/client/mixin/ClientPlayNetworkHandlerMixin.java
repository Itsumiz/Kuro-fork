package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.NewChunks;
import dev.kuro.client.module.impl.Freeze;
import dev.kuro.client.module.impl.TotemPops;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the server's block updates to New Chunks. Injected at the TAIL so it only runs on the game thread
 * (the first, network-thread call of these handlers is thrown away by the game before it reaches the end).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
    /** Server moved us: Freeze lets the confirmation packets through and re-anchors. */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"), require = 0)
    private void kuro$teleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        dev.kuro.client.echo.handlers.SwapStateManager.clear();
        Freeze.onServerTeleport();
        dev.kuro.client.util.ServerSync.onServerStateChange();
    }

    /** Knockback / velocity on us: the step planned this tick is based on old state. TAIL = game thread only. */
    @Inject(method = "handleSetEntityMotion", at = @At("TAIL"), require = 0)
    private void kuro$velocity(net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && packet.getId() == mc.player.getId())
            dev.kuro.client.util.ServerSync.onServerStateChange();
    }

    /**
     * Totem Pops: status 35 = totem.
     * HEAD first so we still see the entity before the game removes it; TAIL as fallback on the game thread.
     */
    /** Totem Pops: status 35 = totem. HEAD so entity still exists. */
    @Inject(method = "handleEntityEvent", at = @At("HEAD"), require = 0)
    private void kuro$entityStatusHead(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.entity.Entity target = packet.getEntity(mc.level);
        byte status = packet.getEventId();
        TotemPops.onStatus(target, status);
    }

    @Inject(method = "handleEntityEvent", at = @At("TAIL"), require = 0)
    private void kuro$entityStatusTail(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.entity.Entity target = packet.getEntity(mc.level);
        TotemPops.onStatus(target, packet.getEventId());
    }

    /** Fast Crystal: a crystal that just appeared can be hit in the same tick. TAIL = game thread, entity exists. */
    @Inject(method = "handleAddEntity", at = @At("TAIL"), require = 0)
    private void kuro$entitySpawn(net.minecraft.network.protocol.game.ClientboundAddEntityPacket packet, CallbackInfo ci) {
        net.minecraft.client.multiplayer.ClientLevel w = net.minecraft.client.Minecraft.getInstance().level;
        if (w == null) return;
        net.minecraft.world.entity.Entity e = w.getEntity(packet.getId());
        if (e != null) dev.kuro.client.module.impl.FastCrystal.onSpawn(e);
    }

    /**
     * Backtrack: the network-thread pass is left alone (the game re-queues the packet onto the game thread); on the game
     * thread Backtrack may take the packet and give it back later. Backtrack.active() is false on the network thread.
     */
    @Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$btMove(net.minecraft.network.protocol.game.ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        if (!dev.kuro.client.module.impl.Backtrack.active()) return;
        net.minecraft.world.entity.Entity e = packet.getEntity(net.minecraft.client.Minecraft.getInstance().level);
        if (e != null && dev.kuro.client.module.impl.Backtrack.intercept((net.minecraft.client.multiplayer.ClientPacketListener) (Object) this, packet, e.getId())) ci.cancel();
    }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$btTeleport(net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        if (!dev.kuro.client.module.impl.Backtrack.active()) return;
        if (dev.kuro.client.module.impl.Backtrack.intercept((net.minecraft.client.multiplayer.ClientPacketListener) (Object) this, packet, packet.id())) ci.cancel();
    }

    @Inject(method = "handleEntityPositionSync", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$btSync(net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        if (!dev.kuro.client.module.impl.Backtrack.active()) return;
        if (dev.kuro.client.module.impl.Backtrack.intercept((net.minecraft.client.multiplayer.ClientPacketListener) (Object) this, packet, packet.id())) ci.cancel();
    }

    /** Sound hook for Auto Fish. TAIL = game thread only. */
    @Inject(method = "handleSoundEvent", at = @At("TAIL"), require = 0)
    private void kuro$sound(net.minecraft.network.protocol.game.ClientboundSoundPacket packet, CallbackInfo ci) {
        String path = packet.getSound().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        dev.kuro.client.module.impl.AutoFish.onSound(path, packet.getX(), packet.getY(), packet.getZ());
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"), require = 0)
    private void kuro$blockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        NewChunks.onBlockUpdate(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"), require = 0)
    private void kuro$deltaUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates((pos, state) -> NewChunks.onBlockUpdate(pos, state));
    }

    /** Respawn / dimension change: the echo swap stack is stale. */
    @Inject(method = "handleRespawn", at = @At("HEAD"), require = 0)
    private void kuro$echoRespawn(net.minecraft.network.protocol.game.ClientboundRespawnPacket packet, CallbackInfo ci) {
        dev.kuro.client.echo.handlers.SwapStateManager.clear();
    }

    /** Performance HUD: the server sends the time about every 20 ticks, so the gap between packets gives the TPS. */
    @Inject(method = "handleSetTime", at = @At("TAIL"), require = 0)
    private void kuro$time(net.minecraft.network.protocol.game.ClientboundSetTimePacket packet, CallbackInfo ci) {
        dev.kuro.client.module.impl.Performance.onTimePacket();
    }
}
