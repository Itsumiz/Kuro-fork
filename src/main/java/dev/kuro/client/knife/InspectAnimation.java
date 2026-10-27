package dev.kuro.client.knife;

/**
 * Keyframe clips. A clip is float[][] where each row is {time 0..1, ch0, ch1, ...}; the value of channel c sits at
 * index c + 1 and missing trailing values are 0. Angles are stored unwrapped (720, 1440 ...) so interpolation gives
 * real multi-turn spins; wrap them only where they are used.
 */
final class InspectAnimation {
    static final int X = 0, Y = 1, Z = 2;            // hand offset
    static final int YAW = 3, PITCH = 4, ROLL = 5;   // degrees
    static final int FLIP = 6;                       // roll around the blade axis
    static final int SPIN = 7;                       // unused by the knives (kept so key rows stay compatible)
    static final int PART_A = 8, PART_B = 9;         // per-part rotation (karambit ring, butterfly halves)
    static final int SHINE = 10;                     // highlight sweep
    static final int SLIDE = 11;                     // grip shift along the knife
    static final int DRAW = 12, SHEATH = 13;         // katana (not ported yet)
    static final int OFF_HAND = 14, OFF_AT = 15;     // off hand (not ported yet)
    static final int CHANNELS = 16;

    private InspectAnimation() {}

    /** Plain Hermite sample with zero tangents on the first/last key, so a clip eases in and out. */
    static float[] sample(float[][] keys, float t, float[] out) { return sample(keys, t, out, null); }

    /**
     * Same, but when {@code clip} has a loop window the keys that open and close the window use the loop's own (wrapped)
     * tangent. Without that, a spin ran at one speed inside the window and a different one in the segments next to it,
     * so the ring visibly lurched as the loop started and ended.
     */
    static float[] sample(float[][] keys, float t, float[] out, Clip clip) {
        t = Math.max(0f, Math.min(1f, t));
        int i = 0;
        while (i < keys.length - 2 && t > keys[i + 1][0]) i++;
        float[] to = keys[i + 1], from = keys[i];
        float span = to[0] - from[0];
        float u = span <= 0f ? 1f : (t - from[0]) / span;
        for (int c = 0; c < CHANNELS; c++) {
            out[c] = hermite(value(from, c), value(to, c), slopeAt(keys, i, c, clip) * span, slopeAt(keys, i + 1, c, clip) * span, u);
        }
        return out;
    }

    private static float slopeAt(float[][] keys, int i, int c, Clip clip) {
        if (clip != null && clip.edgeSlope != null) {
            float t = keys[i][0];
            if (Math.abs(t - clip.loopEnter) < 1e-4f || Math.abs(t - clip.loopExit) < 1e-4f) return clip.edgeSlope[c];
        }
        return slope(keys, i, c);
    }

    /** Looping sample: tangents wrap around the seam and are corrected for drift, so a spin keeps its speed. */
    static float[] sampleLoop(float[][] keys, float t, float[] out) {
        t -= (float) Math.floor(t);
        int n = keys.length;
        int i = 0;
        while (i < n - 2 && t > keys[i + 1][0]) i++;
        float[] to = keys[i + 1], from = keys[i];
        float span = to[0] - from[0];
        float u = span <= 0f ? 1f : (t - from[0]) / span;
        for (int c = 0; c < CHANNELS; c++) {
            out[c] = hermite(value(from, c), value(to, c), loopSlope(keys, i, c) * span, loopSlope(keys, i + 1, c) * span, u);
        }
        return out;
    }

    private static float loopSlope(float[][] keys, int i, int c) {
        int n = keys.length;
        float drift = value(keys[n - 1], c) - value(keys[0], c);
        float prevT, prevV, nextT, nextV;
        if (i == 0) {
            prevT = keys[n - 2][0] - 1f;
            prevV = value(keys[n - 2], c) - drift;
        } else {
            prevT = keys[i - 1][0];
            prevV = value(keys[i - 1], c);
        }
        if (i == n - 1) {
            nextT = keys[1][0] + 1f;
            nextV = value(keys[1], c) + drift;
        } else {
            nextT = keys[i + 1][0];
            nextV = value(keys[i + 1], c);
        }
        return (nextV - prevV) / (nextT - prevT);
    }

    private static float value(float[] key, int channel) {
        return channel + 1 < key.length ? key[channel + 1] : 0f;
    }

    private static float slope(float[][] keys, int i, int c) {
        if (i == 0 || i == keys.length - 1) return 0f;
        float[] prev = keys[i - 1], next = keys[i + 1];
        return (value(next, c) - value(prev, c)) / (next[0] - prev[0]);
    }

    private static float hermite(float p0, float p1, float m0, float m1, float u) {
        float u2 = u * u, u3 = u2 * u;
        return (2f * u3 - 3f * u2 + 1f) * p0 + (u3 - 2f * u2 + u) * m0 + (-2f * u3 + 3f * u2) * p1 + (u3 - u2) * m1;
    }

    /** One animation: keys, length in seconds, and an optional loop window [loopEnter, loopExit] (both must be keys). */
    static final class Clip {
        final float[][] keys;
        final float seconds;
        final float loopEnter, loopExit;
        private final float[][] loop;
        /** Per channel: the loop's tangent at its seam, in whole-clip time units (null when the clip has no loop). */
        private final float[] edgeSlope;

        Clip(float[][] keys, float seconds) { this(keys, seconds, 0f, 0f); }

        Clip(float[][] keys, float seconds, float loopEnter, float loopExit) {
            this.keys = keys;
            this.seconds = seconds;
            this.loopEnter = loopEnter;
            this.loopExit = loopExit;
            this.loop = loopExit > loopEnter ? cut(keys, loopEnter, loopExit) : null;
            if (loop == null) {
                this.edgeSlope = null;
            } else {
                this.edgeSlope = new float[CHANNELS];
                for (int c = 0; c < CHANNELS; c++) edgeSlope[c] = loopSlope(loop, 0, c) / (loopExit - loopEnter);
            }
        }

        boolean loops() { return loop != null; }

        float[] sample(float t, float[] out) {
            if (loop != null && t > loopEnter && t < loopExit) {
                return sampleLoop(loop, (t - loopEnter) / (loopExit - loopEnter), out);
            }
            return InspectAnimation.sample(keys, t, out, this);
        }

        private static float[][] cut(float[][] keys, float enter, float exit) {
            int from = -1, to = -1;
            for (int i = 0; i < keys.length; i++) {
                if (Math.abs(keys[i][0] - enter) < 1e-4f) from = i;
                if (Math.abs(keys[i][0] - exit) < 1e-4f) to = i;
            }
            if (from < 0 || to <= from) throw new IllegalArgumentException("a loop starts and ends on keys");
            float[][] out = new float[to - from + 1][];
            for (int i = from; i <= to; i++) {
                out[i - from] = keys[i].clone();
                out[i - from][0] = (keys[i][0] - enter) / (exit - enter);
            }
            return out;
        }
    }
}
