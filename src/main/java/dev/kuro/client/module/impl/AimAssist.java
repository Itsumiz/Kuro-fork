package dev.kuro.client.module.impl;

import dev.kuro.client.event.EventHandler;
import dev.kuro.client.event.events.RenderEvent;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.AssistAim;
import dev.kuro.client.util.RotationEngine;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Aim Assist. The aim itself (lead, smoothing, hand wobble, mouse-step quantisation) lives in util/AssistAim so that
 * Silent Aura and Trigger Bot can use exactly the same aim with a stronger pull.
 */
public class AimAssist extends Module {
    private final Setting.Num distance = add(new Setting.Num("Distance", 4.5, 1.0, 8.0, 0.1));
    private final Setting.Num maxAngle = add(new Setting.Num("Max Angle", 90, 1, 360, 1));
    private final Setting.Bool aimVertically = add(new Setting.Bool("Aim Vertically", false));
    private final Setting.Num horizontalSpeed = add(new Setting.Num("Horizontal Speed", 5.0, 1.0, 10.0, 0.1));
    private final Setting.Num verticalSpeed = add(new Setting.Num("Vertical Speed", 5.0, 1.0, 10.0, 0.1));
    private final Setting.Bool players = add(new Setting.Bool("Players", true));
    private final Setting.Bool requireMouseDown = add(new Setting.Bool("Require Mouse Down", true));

    private LivingEntity target;
    private final AssistAim aim = new AssistAim();

    public AimAssist() { super("Aim Assist", Category.COMBAT); }

    @Override
    protected void onEnable() { aim.reset(); }

    @Override
    protected void onDisable() { target = null; aim.reset(); }

    @EventHandler
    public void onRender(RenderEvent event) {
        if (mc.player == null || mc.level == null) return;

        if (requireMouseDown.value && !mc.options.keyAttack.isDown()) {
            aim.reset();
            return;
        }

        if (target == null || !target.isAlive() || mc.player.distanceTo(target) > distance.value) {
            target = findTarget();
            if (target == null) {
                aim.reset();
                return;
            }
        }

        float[] d = aim.step(target, mc.player.getYRot(), mc.player.getXRot(),
                horizontalSpeed.value, verticalSpeed.value, aimVertically.value, 1f, 1f);
        if (d != null) {
            // applyTrackedMouseDelta takes mouse units: yaw += x * 0.15, pitch -= y * 0.15 (so the pitch is passed negated)
            RotationEngine.PlayerMouseRotationApplier.applyTrackedMouseDelta(d[0] / 0.15f, -d[1] / 0.15f);
        }
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestDist = distance.value;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || le == mc.player || !le.isAlive()) continue;
            if (le instanceof Player p && !players.value) continue;
            double d = mc.player.distanceTo(le);
            if (d < bestDist) { bestDist = d; best = le; }
        }
        return best;
    }
}
