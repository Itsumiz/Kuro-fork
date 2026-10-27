package dev.kuro.client.mixin;

import dev.kuro.client.util.ForcedKeys;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
public abstract class KeyBindingMixin {
    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$forced(CallbackInfoReturnable<Boolean> cir) {
        KeyMapping self = (KeyMapping) (Object) this;
        if (ForcedKeys.isSuppressed(self)) cir.setReturnValue(false);
        else if (ForcedKeys.isForced(self)) cir.setReturnValue(true);
    }
}
