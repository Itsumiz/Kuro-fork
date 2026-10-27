package dev.kuro.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** KeyMappingAccessor of the echo client: the key a KeyMapping is bound to, and its click counter. */
@Mixin(KeyMapping.class)
public interface EchoKeyMappingAccessor {
    @Accessor("key")
    InputConstants.Key kuro$getKey();

    @Accessor("clickCount")
    int kuro$getClickCount();

    @Accessor("clickCount")
    void kuro$setClickCount(int clickCount);
}
