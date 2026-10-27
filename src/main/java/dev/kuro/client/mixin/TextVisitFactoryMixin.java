package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.UsernameHider;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Username Hider hook: every string the text renderer draws or measures goes through this visitFormatted overload
 * (the shorter overloads call it), so swapping the string here covers chat, tab list, name tags, scoreboard and menus,
 * and keeps the measured width equal to the drawn width.
 */
@Mixin(StringDecomposer.class)
public abstract class TextVisitFactoryMixin {
    @ModifyVariable(
            method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
            at = @At("HEAD"), argsOnly = true)
    private static String kuro$hideName(String text) {
        return UsernameHider.apply(text);
    }
}
