package dev.kuro.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Fast Crystal clear the right-click repeat delay. */
@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {
    @Accessor("rightClickDelay")
    void kuro$setItemUseCooldown(int ticks);

    @Accessor("rightClickDelay")
    int kuro$getItemUseCooldown();
}
