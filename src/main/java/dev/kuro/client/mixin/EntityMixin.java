package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.Freecam;
import dev.kuro.client.module.impl.Freelook;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    // mouse movement steers the freecam instead of the real player
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void kuro$look(double dx, double dy, CallbackInfo ci) {
        if (Freecam.active() && (Object) this == Minecraft.getInstance().player) {
            Freecam.get().look(dx, dy);
            ci.cancel();
        } else if (Freelook.active() && (Object) this == Minecraft.getInstance().player) {
            Freelook.get().look(dx, dy);   // camera only; the player keeps facing the same way
            ci.cancel();
        }
    }

    // Zoom: the mouse turns the view less while zoomed in (same feel at every zoom level)
    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private double kuro$zoomYaw(double v) {
        return (Object) this == Minecraft.getInstance().player ? v * dev.kuro.client.module.impl.Zoom.sensScale() : v;
    }

    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private double kuro$zoomPitch(double v) {
        return (Object) this == Minecraft.getInstance().player ? v * dev.kuro.client.module.impl.Zoom.sensScale() : v;
    }

    // silent rotation: your look vector (crosshair picking, ray casts) follows the rotation the server sees
    @Inject(method = "getViewVector", at = @At("HEAD"), cancellable = true, require = 0)
    private void kuro$silentView(float tickDelta, CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
        if (dev.kuro.client.util.Rotations.active() && (Object) this == Minecraft.getInstance().player)
            cir.setReturnValue(dev.kuro.client.util.Rotations.viewVector());
    }

    // Hitbox Expand: a bigger aiming box for other entities (crosshair / attack picking only)
    @Inject(method = "getPickRadius", at = @At("RETURN"), cancellable = true, require = 0)
    private void kuro$margin(CallbackInfoReturnable<Float> cir) {
        float m = dev.kuro.client.module.impl.HitboxExpand.margin((Entity) (Object) this);
        if (m > 0f) cir.setReturnValue(cir.getReturnValueF() + m);
    }
}
