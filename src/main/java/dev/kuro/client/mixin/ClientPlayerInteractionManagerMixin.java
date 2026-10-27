package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.AnchorBomb;
import dev.kuro.client.module.impl.AutoTunnel;
import dev.kuro.client.module.impl.DamageNumbers;
import dev.kuro.client.module.impl.HitSounds;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.kuro.client.module.impl.FakePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class ClientPlayerInteractionManagerMixin {
    // vanilla cancels block breaking every tick the attack key is not held; Auto Tunnel keeps its progress
    @Inject(method = "stopDestroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$keepBreaking(CallbackInfo ci) {
        if (AutoTunnel.breaking()) ci.cancel();
    }

    // Anchor Bomb watches your own block placements (before / after the vanilla use call)
    @Inject(method = "useItemOn", at = @At("HEAD"), require = 0)
    private void kuro$beforeUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        AnchorBomb.beforeUse(hit);
    }

    @Inject(method = "useItemOn", at = @At("RETURN"), require = 0)
    private void kuro$afterUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        AnchorBomb.afterUse(hit, cir.getReturnValue());
    }

    // attacks on the Fake Player never leave the client: the hit is simulated there instead
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$fakePlayerAttack(Player player, Entity target, CallbackInfo ci) {
        HitSounds.onAttack(target);                       // Hit Sounds sees every attack, clicks and modules alike
        DamageNumbers.onAttack(target);                   // Damage Numbers uses it for "Only my hits"
        if (FakePlayer.onAttack(target)) ci.cancel();
    }
}
