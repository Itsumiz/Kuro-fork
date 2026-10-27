package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.Options;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Sets the horizontal speed of the entity you are riding (boat, horse, pig, minecart...) while you hold a movement key. */
public class EntitySpeed extends Module {
    private final Setting.Num speed = add(new Setting.Num("Speed", 1.0, 0.1, 5.0, 0.1));

    public EntitySpeed() { super("Entity Speed", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mc.player == null || mc.screen != null || Freecam.active()) return;
        Entity vehicle = mc.player.getVehicle();
        if (vehicle == null) return;
        Options o = mc.options;

        double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0);
        double s = (o.keyLeft.isDown() ? 1 : 0) - (o.keyRight.isDown() ? 1 : 0);
        if (f == 0 && s == 0) return;

        double yaw = Math.toRadians(mc.player.getYRot());
        double dx = -Math.sin(yaw) * f + Math.cos(yaw) * s;
        double dz = Math.cos(yaw) * f + Math.sin(yaw) * s;
        double len = Math.hypot(dx, dz);
        dx /= len; dz /= len;

        Vec3 v = vehicle.getDeltaMovement();
        vehicle.setDeltaMovement(dx * speed.value, v.y, dz * speed.value);
    }
}
