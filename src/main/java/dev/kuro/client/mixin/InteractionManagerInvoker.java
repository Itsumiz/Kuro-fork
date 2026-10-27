package dev.kuro.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets a module send the "selected slot" packet at an exact point of a packet sequence. */
@Mixin(MultiPlayerGameMode.class)
public interface InteractionManagerInvoker {
    /** Sends the selected-slot packet if the held slot differs from the last one the server was told. */
    @Invoker("ensureHasSentCarriedItem")
    void kuro$syncSelectedSlot();

    /** Block break cooldown (ticks) between two blocks; No Break Delay keeps it at 0. */
    @Accessor("destroyDelay")
    void kuro$setDestroyDelay(int ticks);
}
