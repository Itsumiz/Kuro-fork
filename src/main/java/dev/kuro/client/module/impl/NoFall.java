package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.lang.reflect.Constructor;

/**
 * Cancels fall damage: once you have fallen far enough, the server is told every tick that you are on the ground,
 * which resets its fall counter. Does nothing while gliding, swimming, flying or in creative.
 */
public class NoFall extends Module {
    private static Constructor<?> ctor;
    private final Setting.Num minFall = add(new Setting.Num("Min fall distance", 2.5, 1.0, 8.0, 0.5));

    public NoFall() { super("No Fall", Category.MOVEMENT); }

    @Override
    public void onTick() {
        LocalPlayer p = mc.player;
        if (p == null || mc.getConnection() == null) return;
        if (p.isCreative() || p.isSpectator() || p.getAbilities().flying || p.isFallFlying()) return;
        if (p.isInWater() || p.isInLava() || p.isPassenger() || p.onClimbable()) return;
        if (p.fallDistance > minFall.value && p.getDeltaMovement().y < 0) send();
    }

    /** Built by reflection so it works whichever way this version's packet constructor is shaped. */
    private void send() {
        try {
            if (ctor == null) {
                for (Constructor<?> c : ServerboundMovePlayerPacket.StatusOnly.class.getConstructors()) {
                    Class<?>[] t = c.getParameterTypes();
                    if (t.length >= 1 && t[0] == boolean.class) { ctor = c; break; }
                }
            }
            if (ctor == null) return;
            Object[] a = new Object[ctor.getParameterCount()];
            a[0] = Boolean.TRUE;                                   // onGround = true
            for (int i = 1; i < a.length; i++) a[i] = Boolean.FALSE;   // horizontalCollision = false
            mc.getConnection().send((Packet<?>) ctor.newInstance(a));
        } catch (ReflectiveOperationException ignored) {}
    }
}
