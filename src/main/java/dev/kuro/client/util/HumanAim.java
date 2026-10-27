package dev.kuro.client.util;

import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared helpers that make rotation controllers look less robotic.
 *
 * Ported behaviours inspired by advanced aim-assist systems:
 *  - multi-band noise (HF + MF + LF)
 *  - aim-strength fade when close to target
 *  - bias accumulation + error-velocity terms
 *  - flick detection (sign change → short boost)
 *  - airborne aim-height compensation
 *  - pitch micro-drift
 *  - tiny undershoot / overshoot
 *  - soft acceleration curve
 *  - wrist-swing cubic Bezier path for larger corrections
 */
public final class HumanAim {
    private HumanAim() {}

    // -------------------------------------------------------------------------
    // Multi-band noise
    // -------------------------------------------------------------------------

    /** High + medium + low frequency noise in roughly [-1, 1]. */
    public static double multiBandNoise(double timeSec, double seed) {
        double t = timeSec + seed * 17.13;
        double hf = Math.sin(t * 62.83) * 0.40
                  + Math.sin(t * 47.12 + 0.8) * 0.25
                  + Math.sin(t * 78.54 + 1.4) * 0.15;
        double mf = Math.sin(t * 12.57) * 0.55
                  + Math.sin(t * 7.85 + 1.2) * 0.35;
        double lf = Math.sin(t * 1.26 + seed) * 0.45
                  + Math.sin(t * 0.94 + 0.5) * 0.30;
        return hf * 0.35 + mf * 0.40 + lf * 0.25;
    }

    public static double tremor(double timeSec, double seed) {
        return multiBandNoise(timeSec, seed);
    }

    public static double addTremor(double vel, double timeSec, double seed,
                                   double noisePct, double maxSpeed, double aimStrength) {
        if (noisePct <= 0) return vel;
        double amp = maxSpeed * 0.018 * (noisePct / 100.0) * (0.25 + 0.75 * aimStrength);
        return vel + multiBandNoise(timeSec, seed) * amp;
    }

    public static double addTremor(double vel, double timeSec, double seed, double noisePct, double maxSpeed) {
        return addTremor(vel, timeSec, seed, noisePct, maxSpeed, 1.0);
    }

    // -------------------------------------------------------------------------
    // Overshoot / undershoot
    // -------------------------------------------------------------------------

    public static double overshootAllowance(double errorAbs, double noisePct) {
        if (noisePct <= 0 || errorAbs < 0.15) return 0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double base = 0.08 + r.nextDouble() * 0.22;
        if (r.nextDouble() < 0.18) base *= 1.6;
        return base * (noisePct / 100.0);
    }

    public static double undershootScale(double errorAbs, double noisePct) {
        if (noisePct <= 0 || errorAbs < 0.4) return 1.0;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() > 0.12 * (noisePct / 100.0)) return 1.0;
        return 0.55 + r.nextDouble() * 0.35;
    }

    // -------------------------------------------------------------------------
    // Acceleration curve
    // -------------------------------------------------------------------------

    public static double accelCurve(double errorAbs, double maxExpectedError) {
        double n = Mth.clamp(errorAbs / Math.max(8.0, maxExpectedError), 0.0, 1.0);
        return 0.55 + 0.60 * Math.sin(n * Math.PI);
    }

    // -------------------------------------------------------------------------
    // Aim strength (fades when close)
    // -------------------------------------------------------------------------

    /**
     * Aim strength in [0.55, 1]. Only softens very close to the target so tracking
     * on a strafing player stays responsive (the previous 1.5–8° fade was too aggressive).
     */
    public static double desiredAimStrength(double combinedErrorDeg) {
        // full strength above ~5°, gentle soften only inside ~2°
        double fade = smoothStep(0.8, 5.0, combinedErrorDeg);
        return 0.55 + 0.45 * fade;   // never drops below 0.55
    }

    public static double blendAimStrength(double current, double desired, double dt) {
        // rise fast, fall slowly – keeps tracking locked when the target strafes
        double rate = desired > current ? Mth.clamp(dt * 12.0, 0.05, 0.55)
                                        : Mth.clamp(dt * 4.0, 0.02, 0.25);
        return current + (desired - current) * rate;
    }

    // -------------------------------------------------------------------------
    // Bias accumulation
    // -------------------------------------------------------------------------

    public static double updateBias(double bias, double error, double aimStrength,
                                    double dt, double speedFactor, double limit) {
        double sq = aimStrength * aimStrength;
        bias += error * dt * sq * (1.0 - speedFactor);
        double retain = 1.0 - (1.0 - aimStrength) * Mth.clamp(dt * 5.0, 0.0, 0.5);
        bias *= retain;
        double lim = limit * (1.0 - speedFactor * 0.9);
        return Mth.clamp(bias, -lim, lim);
    }

    // -------------------------------------------------------------------------
    // Error velocity
    // -------------------------------------------------------------------------

    public static double updateErrorVelocity(double current, double error, double lastError,
                                             double dt, double blend) {
        if (dt < 1e-6) return current;
        double errVel = (error - lastError) / dt;
        return current * (1.0 - blend) + errVel * blend;
    }

    // -------------------------------------------------------------------------
    // Flick detection
    // -------------------------------------------------------------------------

    public static double updateFlickTicks(double flickTicks, double error, double lastSign,
                                          double dt, double threshold, double[] signOut) {
        double sign = Math.signum(error);
        if (sign != 0 && sign != lastSign && Math.abs(error) > threshold) {
            flickTicks = Math.min(flickTicks + 1.0, 8.0);
        } else {
            flickTicks = Math.max(0.0, flickTicks - dt * 3.0);
        }
        signOut[0] = sign != 0 ? sign : lastSign;
        return flickTicks;
    }

    public static double flickFactor(double flickTicks) {
        return Mth.clamp(flickTicks / 5.0, 0.0, 1.0);
    }

    // -------------------------------------------------------------------------
    // Airborne aim-height compensation
    // -------------------------------------------------------------------------

    public static double updateAirFactor(double airFactor, boolean onGround,
                                         double eyeY, double lastGroundEyeY,
                                         double verticalVelocity) {
        if (onGround) {
            return airFactor * 0.35;
        }
        double airborneHeight = Math.max(0.0, eyeY - lastGroundEyeY);
        double velocityAir = Math.min(0.7, Math.max(0.0, verticalVelocity) * 0.34);
        double heightAir = Math.min(0.82, airborneHeight * 0.58);
        return Math.max(airFactor * 0.92, Math.max(velocityAir, heightAir));
    }

    public static double airborneAimY(double targetMinY, double targetMaxY, double playerEyeY,
                                      double airFactor, double baseHeightFrac) {
        double h = targetMaxY - targetMinY;
        double centerY = targetMinY + h * baseHeightFrac;
        double airborneOffset = Math.min(0.85, airFactor);
        double eyeOffset = 0.1 + airborneOffset;
        double eyeAligned = Mth.clamp(playerEyeY - eyeOffset, targetMinY + 0.01, targetMaxY - 0.01);
        double blend = Mth.clamp(airborneOffset / 0.55, 0.0, 1.0);
        double y = eyeAligned + (centerY - eyeAligned) * blend;
        return Mth.clamp(y, targetMinY + 0.01, targetMaxY - 0.01);
    }

    // -------------------------------------------------------------------------
    // Pitch micro-drift
    // -------------------------------------------------------------------------

    public static final class PitchDrift {
        public double pos, velocity, target, noise;
        public long nextNanos;

        public void reset() {
            pos = velocity = target = noise = 0;
            nextNanos = 0;
        }

        public double sample(double playerPitch, double aimStrength, double dt, long nowNanos) {
            double strength = 0.65 + 0.35 * aimStrength;
            if (nextNanos == 0 || nowNanos >= nextNanos) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                double amp = lerp(strength, 0.05, 0.15);
                double correction = playerPitch > 22 ? -0.18 : (playerPitch < -22 ? 0.18 : 0);
                target = Mth.clamp((r.nextDouble() * 2 - 1) * amp + correction, -0.5, 0.5);
                nextNanos = nowNanos + (300 + r.nextInt(420)) * 1_000_000L;
            }
            double spring = lerp(strength, 2.0, 5.0);
            double damping = Math.pow(0.04, dt);
            velocity += (target - pos) * spring * dt;
            velocity *= damping;
            pos += velocity * dt;
            double limit = lerp(strength, 0.45, 0.8);
            if (pos > limit) { pos = limit; velocity = Math.min(0, velocity); }
            else if (pos < -limit) { pos = -limit; velocity = Math.max(0, velocity); }

            noise += (ThreadLocalRandom.current().nextDouble() * 2 - 1 - noise)
                    * Mth.clamp(dt * 5.0, 0.02, 0.18);
            double t = nowNanos * 1e-9;
            double osc = Math.sin(t * 8.7 + 0.4) * 0.35
                       + Math.sin(t * 13.1 + 2.2) * 0.22
                       + Math.sin(t * 19.6 + 1.1) * 0.12
                       + noise * 0.3;
            double restore = -pos * lerp(strength, 1.4, 3.0);
            double out = velocity * 0.25 + restore + osc * lerp(strength, 0.35, 0.9);
            double pitchScale = 1.0 - 0.85 * smoothStep(72, 88, Math.abs(playerPitch));
            double outLimit = lerp(strength, 0.25, 0.55) * pitchScale;
            return Mth.clamp(out, -outLimit, outLimit);
        }
    }

    // -------------------------------------------------------------------------
    // Wrist-swing Bezier
    // -------------------------------------------------------------------------

    public static final class SwingState {
        public double ctrlYaw, ctrlPitch, progress, duration;
        public boolean active;
        public double lastErrYaw, lastErrPitch, seed;

        public void reset() {
            active = false;
            progress = 0;
            ctrlYaw = ctrlPitch = 0;
            duration = 0.18;
            lastErrYaw = lastErrPitch = 0;
            seed = ThreadLocalRandom.current().nextDouble();
        }
    }

    public static float[] wristSwing(double curYaw, double curPitch,
                                     double wantYaw, double wantPitch,
                                     double dt, double noisePct, SwingState state) {
        double errYaw = Mth.wrapDegrees(wantYaw - curYaw);
        double errPitch = wantPitch - curPitch;
        double errAbs = Math.hypot(errYaw, errPitch);

        if (noisePct <= 0 || errAbs < 6.0) {
            state.active = false;
            return new float[]{(float) wantYaw, (float) wantPitch};
        }

        double dErr = Math.hypot(Mth.wrapDegrees(errYaw - state.lastErrYaw),
                                 errPitch - state.lastErrPitch);
        if (!state.active || dErr > 12.0) {
            startSwing(errYaw, errPitch, noisePct, state);
        }
        state.lastErrYaw = errYaw;
        state.lastErrPitch = errPitch;

        state.progress = Mth.clamp(state.progress + dt / state.duration, 0.0, 1.0);
        if (state.progress >= 1.0) {
            state.active = false;
            return new float[]{(float) wantYaw, (float) wantPitch};
        }

        double t = state.progress;
        double u = 1.0 - t;
        double uu = u * u, tt = t * t;
        double uuu = uu * u, ttt = tt * t;
        double b0 = uuu, b1 = 3.0 * uu * t, b2 = 3.0 * u * tt, b3 = ttt;

        double midYaw = curYaw + errYaw * 0.55 + state.ctrlYaw;
        double midPitch = curPitch + errPitch * 0.55 + state.ctrlPitch;

        double outYaw = b0 * curYaw + b1 * (curYaw + state.ctrlYaw) + b2 * midYaw + b3 * wantYaw;
        double outPitch = b0 * curPitch + b1 * (curPitch + state.ctrlPitch) + b2 * midPitch + b3 * wantPitch;
        outPitch = Mth.clamp(outPitch, -89.5, 89.5);
        return new float[]{(float) outYaw, (float) outPitch};
    }

    private static void startSwing(double errYaw, double errPitch, double noisePct, SwingState state) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        state.active = true;
        state.progress = 0.0;
        state.seed = r.nextDouble();
        double errAbs = Math.hypot(errYaw, errPitch);
        state.duration = Mth.clamp(0.11 + errAbs * 0.0045, 0.10, 0.28);

        double len = Math.max(1e-4, errAbs);
        double nx = -errPitch / len;
        double ny = errYaw / len;
        double arc = (1.8 + r.nextDouble() * 3.2) * (noisePct / 100.0);
        if (Math.abs(errYaw) > Math.abs(errPitch) * 1.4) arc *= 0.85;
        else arc *= 1.15;

        double sign = r.nextBoolean() ? 1.0 : -1.0;
        if (r.nextDouble() < 0.65) {
            sign = Math.signum(errYaw + errPitch * 0.3);
            if (sign == 0) sign = 1.0;
        }
        state.ctrlYaw = nx * arc * sign;
        state.ctrlPitch = ny * arc * sign * 0.75;
    }

    // -------------------------------------------------------------------------
    // Math helpers
    // -------------------------------------------------------------------------

    public static double smoothStep(double edge0, double edge1, double x) {
        double n = Mth.clamp((x - edge0) / Math.max(1e-6, edge1 - edge0), 0.0, 1.0);
        return n * n * (3.0 - 2.0 * n);
    }

    public static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
