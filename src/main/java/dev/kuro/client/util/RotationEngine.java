package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class RotationEngine {
    private static final Minecraft mc = Minecraft.getInstance();
    public static final RotationManager INSTANCE = new RotationManager();
    public static final SentRotation SENT = new SentRotation();

    // --- SentRotation ---
    public static class SentRotation {
        private float yaw = Float.NaN;
        private float pitch = Float.NaN;
        private boolean spoofed = false;
        public void update(float y, float p, boolean s) { yaw = y; pitch = p; spoofed = s; }
        public boolean isValid() { return !Float.isNaN(yaw); }
        public boolean isSpoofed() { return spoofed && isValid(); }
        public float yaw() { return yaw; }
        public float pitch() { return pitch; }
        public void reset() { yaw = Float.NaN; pitch = Float.NaN; spoofed = false; }
    }

    // --- MouseRotationController (Abstract Base) ---
    public static abstract class MouseRotationController {
        public float tolerance = 3.0f;
        public float speed = 1.0f;
        public float savedYaw, savedPitch;
        public float renderedYaw, renderedPitch;
        public float pendingYawDelta, pendingPitchDelta;
        private boolean complete;
        private boolean retainAfterCompletion;

        public boolean isComplete() { return complete; }
        public void setComplete(boolean c) { complete = c; }
        public boolean shouldRetainAfterCompletion() { return retainAfterCompletion; }
        public void setRetainAfterCompletion(boolean r) { retainAfterCompletion = r; }

        public abstract boolean updateYaw();
        public abstract boolean updatePitch();

        public void update(LocalPlayer player, boolean screenOpen) {
            if (player == null || screenOpen) return;
            if (updateYaw() && updatePitch() && Math.abs(pendingYawDelta) < 1.0f && Math.abs(pendingPitchDelta) < 1.0f) {
                complete = true;
            }
        }

        protected float getMouseScale() {
            float sens = mc.options.sensitivity().get().floatValue() * 0.6f + 0.2f;
            return sens * sens * sens * 8.0f;
        }

        protected boolean isOutsideTolerance(double error) {
            float rotPerStep = getMouseScale() * 0.15f;
            return Math.round(error / rotPerStep) > Math.max(Math.round(tolerance / rotPerStep), 0L);
        }

        protected float addPendingStep(float pending, double error, double step) {
            return (float) (error > 0.0 ? pending + step : pending - step);
        }

        public void applyPendingMovement(boolean screenOpen) {
            int yawSteps = (int) pendingYawDelta;
            int pitchSteps = (int) pendingPitchDelta;
            float remYaw = pendingYawDelta - yawSteps;
            float remPitch = pendingPitchDelta - pitchSteps;
            float scale = getMouseScale();
            
            if (!screenOpen) {
                PlayerMouseRotationApplier.applyTrackedMouseDelta(yawSteps * scale, pitchSteps * scale);
            }
            pendingYawDelta = remYaw;
            pendingPitchDelta = remPitch;
        }
    }

    // --- FixedRotationController ---
    public static class FixedRotationController extends MouseRotationController {
        public static final float UNSET = -999.0f;
        public float targetYaw, targetPitch;

        public FixedRotationController(float y, float p) { targetYaw = y; targetPitch = p; }
        public void setTargetRotation(float y, float p) { targetYaw = y; targetPitch = p; setComplete(false); }
        public float getCurrentYaw() { return mc.player != null ? mc.player.getYRot() : 0.0f; }
        public float getCurrentPitch() { return mc.player != null ? mc.player.getXRot() : 0.0f; }

        @Override
        public boolean updateYaw() {
            if (targetYaw == UNSET) return true;
            float scale = getMouseScale();
            float predictedYaw = getCurrentYaw() + (int) pendingYawDelta * scale * 0.15f;
            double error = Mth.wrapDegrees(targetYaw - predictedYaw);
            if (!isOutsideTolerance(Math.abs(error))) return true;
            
            double step = speed * 0.25;
            double normErr = Math.abs(error) / 100.0;
            double shifted = normErr + 0.7;
            step *= Math.min(Math.max(1.0, 0.4 + 2.0 * Math.pow(shifted, 3.0) + Math.pow(shifted, 2.0)), 4.0);
            
            pendingYawDelta = addPendingStep(pendingYawDelta, error, step);
            return false;
        }

        @Override
        public boolean updatePitch() {
            if (targetPitch == UNSET) return true;
            float current = getCurrentPitch() == -90.0f ? -89.99f : getCurrentPitch();
            float scale = getMouseScale();
            float predictedPitch = current - (int) (-pendingPitchDelta) * scale * 0.15f;
            double error = Mth.wrapDegrees(targetPitch - predictedPitch);
            if (!isOutsideTolerance(Math.abs(error))) return true;

            double step = speed * 0.25;
            double normErr = Math.abs(error) / 75.0;
            double shifted = normErr + 0.7;
            step *= Math.max(1.0, 0.4 + 2.0 * Math.pow(shifted, 3.0) + Math.pow(shifted, 2.0));

            pendingPitchDelta = addPendingStep(pendingPitchDelta, error, step);
            return false;
        }
    }

    // --- AdaptiveRotationController ---
    public static class AdaptiveRotationController extends FixedRotationController {
        private Vec3 target;
        private boolean relativeMode;
        private float currentYaw, currentPitch;

        public AdaptiveRotationController(float y, float p) {
            super(y, p);
            currentYaw = mc.player != null ? mc.player.getYRot() : y;
            currentPitch = mc.player != null ? mc.player.getXRot() : p;
            renderedYaw = currentYaw;
            renderedPitch = currentPitch;
        }

        public void setTarget(Vec3 t) { target = t; setComplete(false); }

        @Override
        public void update(LocalPlayer player, boolean screenOpen) {
            if (player == null || screenOpen) return;
            if (target != null) {
                Vec3 eye = player.getEyePosition();
                double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
                double hyp = Math.sqrt(dx * dx + dz * dz);
                setTargetRotation((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f, (float) -Math.toDegrees(Math.atan2(dy, hyp)));
            }
            boolean yawDone = updateYaw();
            boolean pitchDone = updatePitch();
            setComplete(yawDone && pitchDone && Math.abs(pendingYawDelta) < 1.0f && Math.abs(pendingPitchDelta) < 1.0f);
        }

        public float getRenderedYaw() { return relativeMode && mc.player != null ? mc.player.getYRot() + currentYaw : currentYaw; }
        public float getRenderedPitch() { return Mth.clamp(relativeMode && mc.player != null ? mc.player.getXRot() + currentPitch : currentPitch, -90.0f, 90.0f); }
    }

    // --- PlayerMouseRotationApplier (Exact Vape) ---
    public static class PlayerMouseRotationApplier {
        public static void applyTrackedMouseDelta(float yawDelta, float pitchDelta) {
            if (mc.player == null) return;
            float trackedPitch = mc.player.getXRot();
            float trackedYaw = mc.player.getYRot();
            mc.player.setYRot((float)(trackedYaw + yawDelta * 0.15));
            mc.player.setXRot((float)(trackedPitch - pitchDelta * 0.15));
            if (mc.player.getXRot() < -90.0f) mc.player.setXRot(-90.0f);
            if (mc.player.getXRot() > 90.0f) mc.player.setXRot(90.0f);
            mc.player.xRotO += mc.player.getXRot() - trackedPitch;
            mc.player.yRotO += mc.player.getYRot() - trackedYaw;
        }
    }

    // --- RotationManager (Exact Vape) ---
    public static class RotationManager {
        private AdaptiveRotationController activeController;
        private float managedYaw, managedPitch;

        public boolean isControllerUpdating() { return activeController != null && !activeController.isComplete(); }
        public float getManagedYaw() { return isControllerUpdating() ? managedYaw : (mc.player != null ? mc.player.getYRot() : 0f); }
        public float getManagedPitch() { return isControllerUpdating() ? managedPitch : (mc.player != null ? mc.player.getXRot() : 0f); }

        public void setController(AdaptiveRotationController c) {
            if (activeController == null && c != null) {
                managedYaw = c.getRenderedYaw();
                managedPitch = c.getRenderedPitch();
            }
            activeController = c;
        }

        public void advanceAndApplyNow(int iterations) {
            if (activeController == null || mc.player == null) return;
            for (int i = 0; i < iterations; i++) {
                activeController.update(mc.player, false);
                activeController.applyPendingMovement(false);
            }
            managedYaw = activeController.getRenderedYaw();
            managedPitch = activeController.getRenderedPitch();
            applyManagedRotation(true);
        }

        public void applyManagedRotation(boolean apply) {
            if (mc.player == null) return;
            if (apply) {
                mc.player.setYRot(managedYaw);
                mc.player.yHeadRot = managedYaw;
                mc.player.setXRot(managedPitch);
            }
        }

        public void release() {
            activeController = null;
            SENT.reset();
        }
    }
}