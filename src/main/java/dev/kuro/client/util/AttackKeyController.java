package dev.kuro.client.util;

import dev.kuro.client.event.EventBus;
import dev.kuro.client.event.events.SyntheticAttackEvent;
import dev.kuro.client.module.Module;
import net.minecraft.client.Minecraft;

public class AttackKeyController {
    private static final Minecraft mc = Minecraft.getInstance();

    public static boolean requestSyntheticAttack(Module source) {
        SyntheticAttackEvent event = new SyntheticAttackEvent(source);
        EventBus.INSTANCE.post(event);
        
        if (event.isCancelled()) {
            return false;
        }

        if (mc.options != null && mc.options.keyAttack != null) {
            // Force a physical click by toggling the keybinding
            mc.options.keyAttack.setDown(true);
            // The vanilla client will process this on the next tick/frame, guaranteeing SWING -> INTERACT order
            return true;
        }
        return false;
    }
    
    public static void releaseAttackKey() {
        if (mc.options != null && mc.options.keyAttack != null) {
            mc.options.keyAttack.setDown(false);
        }
    }
}