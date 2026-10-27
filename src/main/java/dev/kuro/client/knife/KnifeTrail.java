package dev.kuro.client.knife;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Motion trail: remembers where each streak's base and tip were over the last fraction of a second and draws a ribbon
 * between consecutive samples that fades out with age. Positions are stored in the item frame, so the ribbon is drawn
 * in the same frame the knife is (it follows the hand sway instead of staying fixed in the world).
 */
final class KnifeTrail {
    /** Samples further apart than this are not joined (animation restarted, frame hitch). */
    private static final long MAX_GAP = 100_000_000L;
    private static final long MIN_GAP = 2_000_000L;

    private record Sample(long time, float[] ends) {}

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    void clear() { samples.clear(); }

    boolean isEmpty() { return samples.isEmpty(); }

    void add(long now, float[] ends) {
        Sample last = samples.peekLast();
        if (last != null && now - last.time < MIN_GAP) return;
        samples.addLast(new Sample(now, ends.clone()));
    }

    void expire(long now, float seconds) {
        long keep = (long) (seconds * 1.0e9f);
        while (!samples.isEmpty() && now - samples.peekFirst().time > keep) samples.removeFirst();
    }

    /** 16 floats per quad: 4 corners of (x, y, z, alpha 0..1). */
    float[] build(long now, float seconds, KnifeSkin skin) {
        float[] out = new float[256];
        int n = 0;
        Sample prev = null;
        float span = seconds * 1.0e9f;
        for (Sample cur : samples) {
            if (prev != null && cur.time - prev.time <= MAX_GAP) {
                float wa = fade((now - prev.time) / span), wb = fade((now - cur.time) / span);
                for (int s = 0; s < skin.streakCount(); s++) {
                    float strength = skin.streakStrength(s);
                    float tipA = wa * strength * 0.6f, tipB = wb * strength * 0.6f;
                    if (tipA < 0.004f && tipB < 0.004f) continue;
                    if (n + 16 > out.length) out = Arrays.copyOf(out, out.length * 2);
                    int i = s * 6;
                    n = corner(out, n, prev.ends, i, tipA * 0.2f);       // base, older
                    n = corner(out, n, prev.ends, i + 3, tipA);          // tip, older
                    n = corner(out, n, cur.ends, i + 3, tipB);           // tip, newer
                    n = corner(out, n, cur.ends, i, tipB * 0.2f);        // base, newer
                }
            }
            prev = cur;
        }
        return Arrays.copyOf(out, n);
    }

    private static float fade(float age) {
        float k = Math.max(0.0f, 1.0f - age);
        return k * k;
    }

    private static int corner(float[] out, int n, float[] ends, int at, float alpha) {
        out[n] = ends[at];
        out[n + 1] = ends[at + 1];
        out[n + 2] = ends[at + 2];
        out[n + 3] = alpha;
        return n + 4;
    }

    /** Writes the quads from {@link #build}, both windings so no culling setting can hide the ribbon. */
    static void emit(PoseStack.Pose pose, VertexConsumer out, float[] quads, int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        for (int q = 0; q + 16 <= quads.length; q += 16) {
            for (int pass = 0; pass < 2; pass++) {
                for (int v = 0; v < 4; v++) {
                    int i = q + (pass == 0 ? v : 3 - v) * 4;
                    out.addVertex(pose, quads[i], quads[i + 1], quads[i + 2])
                            .setColor(r, g, b, Math.round(Math.min(1.0f, quads[i + 3]) * 255.0f))
                            .setUv(0.5f, 0.5f)
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(0xF000F0)
                            .setNormal(pose, 0.0f, 0.0f, 1.0f);
                }
            }
        }
    }
}
