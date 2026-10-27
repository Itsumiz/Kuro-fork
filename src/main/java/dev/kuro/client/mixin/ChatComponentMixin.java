package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.ChatTimestamps;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Chat Timestamps hook: every message (player chat, system, our own) is added through this overload (the one-argument
 * addMessage calls it), so prefixing the component here stamps each line exactly once.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"), argsOnly = true)
    private Component kuro$timestamp(Component message) {
        return ChatTimestamps.stamp(dev.kuro.client.module.impl.ChatMentions.process(message));
    }
}
