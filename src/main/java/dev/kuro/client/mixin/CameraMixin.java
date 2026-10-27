package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.Freecam;
import dev.kuro.client.module.impl.Freelook;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("TAIL"))
    private void kuro$update(Level area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
                              float tickProgress, CallbackInfo ci) {
        if (Freecam.active()) {
            Freecam f = Freecam.get();
            setRotation(f.yaw, f.pitch);
            setPosition(f.lx(tickProgress), f.ly(tickProgress), f.lz(tickProgress));
        } else if (Freelook.showing()) {
            // The player is the pivot: the camera sits at the player's eyes and (third person) is pushed straight back from
            // them along the freelook direction, stopping at walls. Done here instead of relying on the game's own third-person
            // code, so it orbits the player whether or not the hooks below applied.
            Freelook fl = Freelook.get();
            setRotation(fl.yaw, fl.pitch);
            Vec3 eye = focusedEntity.getEyePosition(tickProgress);
            double back = fl.distanceBlocks() * fl.ease();
            double yr = Math.toRadians(fl.yaw), pr = Math.toRadians(fl.pitch);
            Vec3 look = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
            if (back > 0.01) {
                Vec3 to = eye.subtract(look.scale(back));
                var hit = area.clip(new ClipContext(eye, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, focusedEntity));
                if (hit.getType() != HitResult.Type.MISS) back = Math.max(0.0, hit.getLocation().distanceTo(eye) - 0.25);
            } else back = 0.0;
            setPosition(eye.x - look.x * back, eye.y - look.y * back, eye.z - look.z * back);
        }
    }

    private static boolean kuro$orbitApplied;

    // Freelook "Third person": make the game take its normal third-person branch (camera pulled back, stops at walls, your model is drawn)
    @ModifyVariable(method = "setup", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private boolean kuro$forceThirdPerson(boolean thirdPerson) {
        kuro$orbitApplied = false;
        Freelook.frameStatic();                       // once per frame: pull-back and glide-back progress
        return thirdPerson || Freelook.thirdPerson();
    }

    // ...and give that branch the freelook angles instead of the player's, so the camera orbits around you
    @ModifyArgs(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0), require = 0)
    private void kuro$orbit(Args args) {
        if (Freelook.showing()) {
            args.set(0, Freelook.get().yaw);
            args.set(1, Freelook.get().pitch);
            kuro$orbitApplied = true;
        }
    }

    // Freelook distance: the game's own third-person distance (4 blocks) is scaled by the setting, then pulled in / out smoothly.
    // Scaling the INPUT keeps the wall check, so the camera still stops at blocks.
    @ModifyVariable(method = "getMaxZoom", at = @At("HEAD"), argsOnly = true, require = 0)
    private float kuro$distance(float max) {
        return Freelook.showing() ? max * Freelook.get().distanceScale() : max;
    }

    @Inject(method = "getMaxZoom", at = @At("RETURN"), cancellable = true, require = 0)
    private void kuro$pull(float max, CallbackInfoReturnable<Float> cir) {
        if (Freelook.showing()) cir.setReturnValue(cir.getReturnValueF() * Freelook.get().ease());
    }

    // makes the real player model render while the camera is detached
    @Inject(method = "isDetached", at = @At("HEAD"), cancellable = true)
    private void kuro$thirdPerson(CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.active() || Freelook.thirdPerson()) cir.setReturnValue(true);
    }
}
