package dev.kuro.client.knife;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.Arrays;

/** A voxel model baked into quads (hidden faces culled), with the shine highlight applied at draw time. */
final class KnifeMesh {
    private static final float SHINE_WIDTH = 2.6f;
    private static final float SHINE_BOOST = 1.5f;
    private static final float WIPE_FRONT = 1.5f;
    private static final float GLINT_UV = 0.09f;

    final float[] pos;       // 12 floats per quad (4 corners)
    final float[] normal;    // 3 per quad
    final byte[] material;   // 1 per quad
    final float[] tone;      // 1 per quad (baked grain)
    final int quads;
    private final float shineFrom, shineTo;

    private KnifeMesh(float[] pos, float[] normal, byte[] material, float[] tone, int quads, float shineFrom, float shineTo) {
        this.pos = pos;
        this.normal = normal;
        this.material = material;
        this.tone = tone;
        this.quads = quads;
        this.shineFrom = shineFrom;
        this.shineTo = shineTo;
    }

    /** Writes every quad. {@code pose} already carries the knife/part transform. */
    void emit(PoseStack.Pose pose, VertexConsumer out, int[] palette, int light, Shine shine) {
        boolean shining = shine.gain() > 0.004f;
        for (int q = 0; q < quads; q++) {
            int mat = material[q];
            int argb = palette[mat];
            boolean lit = shining && (shine.tail() > 0.0f ? KnifeMaterial.blade(mat) : KnifeMaterial.metallic(mat));
            float nx = normal[q * 3], ny = normal[q * 3 + 1], nz = normal[q * 3 + 2];
            for (int v = 0; v < 4; v++) {
                int p = q * 12 + v * 3;
                float x = pos[p], y = pos[p + 1], z = pos[p + 2];
                float bright = tone[q];
                if (lit) bright += shine.gain() * SHINE_BOOST * profile(x, shine);
                out.addVertex(pose, x, y, z)
                        .setColor(tint(argb, bright))
                        .setUv((x + z) * GLINT_UV, y * GLINT_UV)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, nx, ny, nz);
            }
        }
    }

    private float profile(float x, Shine shine) {
        float d = x - (shineFrom + (shineTo - shineFrom) * shine.at());
        if (shine.tail() <= 0.0f) {
            float k = d / SHINE_WIDTH;
            return (float) Math.exp(-k * k);
        }
        if (d > 0.0f) {
            float k = d / WIPE_FRONT;
            return (float) Math.exp(-k * k);
        }
        return (float) Math.exp(d / shine.tail());
    }

    static int tint(int argb, float k) {
        int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
        if (k <= 1.0f) {
            r = (int) (r * k);
            g = (int) (g * k);
            b = (int) (b * k);
        } else {
            float w = Math.min(1.0f, k - 1.0f);
            r += (int) ((255 - r) * w);
            g += (int) ((255 - g) * w);
            b += (int) ((255 - b) * w);
        }
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** at = sweep position 0..1 along the blade, gain = brightness, tail > 0 = long "wipe" tail (katana, not ported). */
    record Shine(float at, float gain, float tail) {
        static final Shine NONE = new Shine(0.0f, 0.0f, 0.0f);
    }

    /** Sparse voxel grid, x/y in -64..63 / -32..31, z in -8..7. */
    static final class Voxels {
        private static final int SX = 128, SY = 64, SZ = 16, OX = 64, OY = 32, OZ = 8;
        private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        private final byte[] cells = new byte[SX * SY * SZ];
        private final float[] shade = new float[SX * SY * SZ];

        void set(int x, int y, int z, int material) { set(x, y, z, material, 1.0f); }

        void set(int x, int y, int z, int material, float tone) {
            int i = index(x, y, z);
            cells[i] = (byte) (material + 1);
            shade[i] = tone;
        }

        void clear(int x, int y, int z) {
            int ix = x + OX, iy = y + OY, iz = z + OZ;
            if (ix < 0 || iy < 0 || iz < 0 || ix >= SX || iy >= SY || iz >= SZ) return;
            cells[(ix * SY + iy) * SZ + iz] = 0;
        }

        boolean filled(int x, int y, int z) {
            int ix = x + OX, iy = y + OY, iz = z + OZ;
            if (ix < 0 || iy < 0 || iz < 0 || ix >= SX || iy >= SY || iz >= SZ) return false;
            return cells[(ix * SY + iy) * SZ + iz] != 0;
        }

        KnifeMesh mesh(float shineFrom, float shineTo) {
            int capacity = 256;
            float[] pos = new float[capacity * 12];
            float[] normal = new float[capacity * 3];
            byte[] material = new byte[capacity];
            float[] tone = new float[capacity];
            int quads = 0;
            for (int ix = 0; ix < SX; ix++) {
                for (int iy = 0; iy < SY; iy++) {
                    for (int iz = 0; iz < SZ; iz++) {
                        int cell = (ix * SY + iy) * SZ + iz;
                        if (cells[cell] == 0) continue;
                        int x = ix - OX, y = iy - OY, z = iz - OZ;
                        for (int[] f : FACES) {
                            if (filled(x + f[0], y + f[1], z + f[2])) continue;
                            if (quads == capacity) {
                                capacity *= 2;
                                pos = Arrays.copyOf(pos, capacity * 12);
                                normal = Arrays.copyOf(normal, capacity * 3);
                                material = Arrays.copyOf(material, capacity);
                                tone = Arrays.copyOf(tone, capacity);
                            }
                            face(pos, quads * 12, x, y, z, f);
                            normal[quads * 3] = f[0];
                            normal[quads * 3 + 1] = f[1];
                            normal[quads * 3 + 2] = f[2];
                            material[quads] = (byte) (cells[cell] - 1);
                            tone[quads] = shade[cell];
                            quads++;
                        }
                    }
                }
            }
            return new KnifeMesh(Arrays.copyOf(pos, quads * 12), Arrays.copyOf(normal, quads * 3),
                    Arrays.copyOf(material, quads), Arrays.copyOf(tone, quads), quads, shineFrom, shineTo);
        }

        private static void face(float[] out, int at, int x, int y, int z, int[] f) {
            float x0 = x, x1 = x + 1, y0 = y, y1 = y + 1, z0 = z, z1 = z + 1;
            float[] c = f[0] == 1 ? new float[]{x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1}
                    : f[0] == -1 ? new float[]{x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0}
                    : f[1] == 1 ? new float[]{x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0}
                    : f[1] == -1 ? new float[]{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}
                    : f[2] == 1 ? new float[]{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1}
                    : new float[]{x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0};
            System.arraycopy(c, 0, out, at, 12);
        }

        private static int index(int x, int y, int z) {
            return ((x + OX) * SY + (y + OY)) * SZ + (z + OZ);
        }
    }
}
