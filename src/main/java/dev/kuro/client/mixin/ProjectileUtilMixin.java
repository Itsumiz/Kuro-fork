package dev.kuro.client.mixin;

import dev.kuro.client.module.impl.Backtrack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * Backtrack (Ghost hitbox mode): the crosshair / attack picking asks every candidate entity for its bounding box inside
 * ProjectileUtil.getEntityHitResult(Entity, Vec3, Vec3, AABB, Predicate, double). For tracked players we hand it the box
 * from where they were Delay ms ago instead. Only this one call is redirected, so collision, rendering and everything
 * else still use the real box. It is a MixinExtras @WrapOperation, not a vanilla @Redirect: a @Redirect here made MixinExtras
 * 0.5.4 crash the game on join (ClassCastException in FactoryRedirectWrapperMixinTransformer). require = 0: if the method signature differs in your Minecraft version this simply does
 * nothing (Ghost hitbox then has no effect) instead of crashing the game.
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
    @WrapOperation(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"),
            require = 0)
    private static AABB kuro$ghostBox(Entity entity, Operation<AABB> original) {
        AABB ghost = Backtrack.ghostBox(entity);
        return ghost != null ? ghost : original.call(entity);
    }
}
