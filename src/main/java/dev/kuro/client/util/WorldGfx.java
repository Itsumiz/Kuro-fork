package dev.kuro.client.util;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * World-space ESP renderer, the same idea Meteor Client uses (Renderer3D):
 *
 *  - boxes are queued as real world coordinates while the world is being rendered (WorldRenderEvents),
 *  - everything is written into ONE vertex buffer and drawn with ONE draw call on the GPU,
 *  - it goes through the game's own projection / view matrices, so a box can never slide off its block
 *    (FOV, sprint zoom, view bobbing, hurt tilt, aspect ratio are all handled by the game itself),
 *  - the pipeline has no depth test, so boxes show through walls.
 *
 * The old way (projecting points on the CPU and drawing hundreds of GUI rectangles) both drifted and was slow,
 * because every g.fill() is a separate GUI element in the newer renderer.
 *
 * Lines are drawn as thin camera-independent "ribbons" (two crossed quads per edge) whose width is kept
 * at roughly a constant number of screen pixels, so only one pipeline (quads) is needed.
 */
public final class WorldGfx {
    private WorldGfx() {}

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)   // = Mojang's DEBUG_FILLED_SNIPPET
                    .withLocation(Identifier.fromNamespaceAndPath("kuro", "pipeline/esp"))
                    // DEBUG_FILLED_SNIPPET is TRIANGLE_STRIP; we write 4-vertex quads, so force QUADS
                    .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .build());

    /**
     * Same quads, but depth-tested (no depth write): geometry hides behind terrain. Used by cosmetics (Player Trails) that should
     * not show through walls the way ESP does. Select it with setDepthTest(true) while drawing.
     */
    private static final RenderPipeline DEPTH_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("kuro", "pipeline/esp_depth"))
                    .withVertexFormat(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .build());

    /**
     * Shader pipeline for chunk markers (glowing squares / box faces / dots). Same pass setup as PIPELINE, but quads carry
     * UVs and a custom fragment shader (core/world_marker.fsh) draws constant-pixel-width glowing edges and a frosted fill.
     */
    private static final RenderPipeline SHADER_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("kuro", "pipeline/world_marker"))
                    .withVertexShader(Identifier.fromNamespaceAndPath("kuro", "core/ui_shape"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("kuro", "core/world_marker"))
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .build());


    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(1 << 18);
    private static final ByteBufferBuilder DEPTH_ALLOCATOR = new ByteBufferBuilder(1 << 17);    // separate: two builders can't share one
    private static final ByteBufferBuilder SHADER_ALLOCATOR = new ByteBufferBuilder(1 << 16);   // separate: two builders can't share one
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final Matrix4f MAT = new Matrix4f();
    private static final Vector3f TMP = new Vector3f();

    private static final java.util.Set<String> LOGGED = new java.util.HashSet<>();
    private static BufferBuilder buf;       // where draw calls go right now: plainBuf (through walls) or depthBuf (hidden by terrain)
    private static BufferBuilder plainBuf, depthBuf;
    private static final Vector3f CAM_RIGHT = new Vector3f(1f, 0f, 0f), CAM_UP = new Vector3f(0f, 1f, 0f);
    private static BufferBuilder sbuf;      // shader quads (position + uv + colour)
    private static double cx, cy, cz;       // camera position
    private static double pxPerUnit;        // world size of one framebuffer pixel, per block of distance
    private static float guiScale = 1f;

    /** Call once from the mod initializer so the pipeline is registered before first use. */
    public static void init() {}

    // ------------------------------------------------------------------ frame lifecycle

    /** Start collecting geometry for this frame. */
    public static void begin(Minecraft mc, PoseStack stack, Vec3 cam) {
        MAT.set(stack.last().pose());
        cx = cam.x; cy = cam.y; cz = cam.z;
        double fbH = Math.max(1, mc.getWindow().getHeight());
        pxPerUnit = 2.0 * Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0) / dev.kuro.client.module.impl.Zoom.factor() / fbH;
        guiScale = (float) mc.getWindow().getGuiScale();
        plainBuf = new BufferBuilder(ALLOCATOR, PIPELINE.getVertexFormatMode(), PIPELINE.getVertexFormat());
        depthBuf = new BufferBuilder(DEPTH_ALLOCATOR, DEPTH_PIPELINE.getVertexFormatMode(), DEPTH_PIPELINE.getVertexFormat());
        buf = plainBuf;
        MAT.getRow(0, CAM_RIGHT);        // camera axes in world space = rows of the view rotation (for camera-facing quads)
        MAT.getRow(1, CAM_UP);
        sbuf = new BufferBuilder(SHADER_ALLOCATOR, SHADER_PIPELINE.getVertexFormatMode(), SHADER_PIPELINE.getVertexFormat());
    }

    /** Upload everything queued this frame and draw it: one call for the plain geometry, one for the shader markers. */
    public static void end(Minecraft mc) {
        BufferBuilder b = plainBuf, d = depthBuf, sb = sbuf;
        buf = null;
        plainBuf = null;
        depthBuf = null;
        sbuf = null;
        flush(mc, d, DEPTH_PIPELINE, "kuro depth");     // behind-terrain cosmetics first, so ESP draws on top of them
        flush(mc, b, PIPELINE, "kuro esp");
        flush(mc, sb, SHADER_PIPELINE, "kuro markers");
    }

        private static void flush(Minecraft mc, BufferBuilder b, RenderPipeline pipeline, String label) {
        if (b == null) return;
        MeshData built = b.build();
        if (built == null) return;
        try {
            var dp = built.drawState();
            var format = dp.format();
            GpuBuffer vertices = format.uploadImmediateVertexBuffer(built.vertexBuffer());

            // Mojang 1.21.x: DynamicUniforms.writeTransform(...)
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                    .writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

            var fb = mc.getMainRenderTarget();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> label, fb.getColorTextureView(), OptionalInt.empty(),
                    fb.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, vertices);
                // QUADS need the shared sequential index buffer (6 indices per 4 vertices)
                int ic = dp.indexCount();
                if (ic > 0) {
                    var seq = RenderSystem.getSequentialBuffer(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
                    GpuBuffer idx = seq.getBuffer(ic);
                    pass.setIndexBuffer(idx, seq.type());
                    pass.drawIndexed(0, 0, ic, 1);
                }
            }
        } catch (Throwable t) {
            // ESP is best-effort; never crash the client, but do say what broke (once per label)
            if (LOGGED.add(label)) org.slf4j.LoggerFactory.getLogger("kuro").error("WorldGfx flush failed for " + label, t);
        } finally {
            built.close();
        }
    }


        public static boolean active() { return buf != null; }

    // ------------------------------------------------------------------ drawing API (world coordinates)

    /**
     * An axis-aligned box. line / fill are ARGB (fill alpha 0 = no fill). thickness is in the same
     * "1..3" units as the module settings. glow adds a soft wide halo around every edge.
     */
    public static void box(double x1, double y1, double z1, double x2, double y2, double z2,
                           int line, int fill, int thickness, boolean glow) {
        if (buf == null) return;
        if ((fill >>> 24) != 0) fillBox(x1, y1, z1, x2, y2, z2, fill);

        double dx = (x1 + x2) / 2 - cx, dy = (y1 + y2) / 2 - cy, dz = (z1 + z2) / 2 - cz;
        double dist = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double w = dist * pxPerUnit * Math.max(1.5, thickness * guiScale * 0.75);   // ~constant screen width
        if (glow) edges(x1, y1, z1, x2, y2, z2, w * 3.5, (line & 0xFFFFFF) | (0x38 << 24));
        edges(x1, y1, z1, x2, y2, z2, w, line);
    }

    /**
     * One translucent ghost block: only the faces in faceMask (bit 0 down, 1 up, 2 north, 3 south, 4 west, 5 east) are drawn,
     * flat-shaded like solidBox, with the given alpha (0..255). The pipeline has no depth test, so ghosts show through walls.
     */
    public static void ghostBlock(double x1, double y1, double z1, double x2, double y2, double z2, int rgb, int alpha, int faceMask) {
        if (buf == null || alpha <= 0) return;
        int a = Math.min(255, alpha) << 24;
        int top = (shade(rgb, 1.00f) & 0xFFFFFF) | a, ns = (shade(rgb, 0.80f) & 0xFFFFFF) | a;
        int ew = (shade(rgb, 0.62f) & 0xFFFFFF) | a, bot = (shade(rgb, 0.45f) & 0xFFFFFF) | a;
        if ((faceMask & 2) != 0) quad(x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, top);
        if ((faceMask & 1) != 0) quad(x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, bot);
        if ((faceMask & 4) != 0) quad(x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, ns);
        if ((faceMask & 8) != 0) quad(x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, ns);
        if ((faceMask & 16) != 0) quad(x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, ew);
        if ((faceMask & 32) != 0) quad(x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, ew);
    }

    /**
     * A box drawn as corner brackets only (an L at each of the 8 corners, 'len' blocks long, never more than half a side), so a big
     * outline stays light and readable instead of a heavy wireframe. line / fill are ARGB; no glow.
     */
    public static void cornerBox(double x1, double y1, double z1, double x2, double y2, double z2,
                                 int line, int fill, int thickness, double len) {
        if (buf == null) return;
        if ((fill >>> 24) != 0) fillBox(x1, y1, z1, x2, y2, z2, fill);
        double dx = (x1 + x2) / 2 - cx, dy = (y1 + y2) / 2 - cy, dz = (z1 + z2) / 2 - cz;
        double dist = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double w = dist * pxPerUnit * Math.max(1.5, thickness * guiScale * 0.75);
        double lx = Math.min(len, (x2 - x1) / 2), ly = Math.min(len, (y2 - y1) / 2), lz = Math.min(len, (z2 - z1) / 2);
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) for (int k = 0; k < 2; k++) {
            double x = i == 0 ? x1 : x2, y = j == 0 ? y1 : y2, z = k == 0 ? z1 : z2;
            double sx = i == 0 ? 1 : -1, sy = j == 0 ? 1 : -1, sz = k == 0 ? 1 : -1;
            ribbon(Math.min(x, x + sx * lx), y, z, Math.max(x, x + sx * lx), y, z, 0, w, line);
            ribbon(x, Math.min(y, y + sy * ly), z, x, Math.max(y, y + sy * ly), z, 1, w, line);
            ribbon(x, y, Math.min(z, z + sz * lz), x, y, Math.max(z, z + sz * lz), 2, w, line);
        }
    }

    /**
     * Filled box with shaded faces (top brightest, bottom faintest) plus a normal outline: the "filled" hitbox look.
     * rgb has no alpha; fillAlpha is 0..255 for the brightest face (0 = outline only).
     */
    public static void shadedBox(double x1, double y1, double z1, double x2, double y2, double z2,
                                 int rgb, int fillAlpha, int line, int thickness, boolean glow) {
        if (buf == null) return;
        if (fillAlpha > 0) {
            int top = (Math.min(255, fillAlpha) << 24) | (rgb & 0xFFFFFF);
            int ns = ((fillAlpha * 4 / 5) << 24) | (rgb & 0xFFFFFF);
            int ew = ((fillAlpha * 3 / 5) << 24) | (rgb & 0xFFFFFF);
            int bot = ((fillAlpha * 2 / 5) << 24) | (rgb & 0xFFFFFF);
            quad(x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, bot);   // bottom
            quad(x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, top);   // top
            quad(x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, ns);    // north
            quad(x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, ns);    // south
            quad(x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, ew);    // west
            quad(x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, ew);    // east
        }
        box(x1, y1, z1, x2, y2, z2, line, 0, thickness, glow);
    }

    /**
     * Opaque, flat-shaded box that only draws the faces turned toward the camera. The pipeline has no depth test, so
     * callers that draw several of these must submit them far-to-near (painter's order). No outline.
     */
    public static void solidBox(double x1, double y1, double z1, double x2, double y2, double z2, int rgb) {
        if (buf == null) return;
        int top = shade(rgb, 1.00f), ns = shade(rgb, 0.80f), ew = shade(rgb, 0.62f), bot = shade(rgb, 0.45f);
        if (cy > y2) quad(x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, top);     // top
        if (cy < y1) quad(x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, bot);     // bottom
        if (cz < z1) quad(x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, ns);      // north
        if (cz > z2) quad(x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, ns);      // south
        if (cx < x1) quad(x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, ew);      // west
        if (cx > x2) quad(x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, ew);      // east
    }

    /**
     * One opaque triangle (drawn as a degenerate quad: v0,v1,v2,v2) so it rides the existing QUADS pipeline.
     * Colour is flat-shaded by a simple average light term based on camera height.
     */
    public static void solidTri(double x0, double y0, double z0,
                                double x1, double y1, double z1,
                                double x2, double y2, double z2, int rgb) {
        solidTriColored(x0, y0, z0, rgb, x1, y1, z1, rgb, x2, y2, z2, rgb);
    }

    /** Triangle with per-vertex colours (lets skin/texture samples show detail). */
    public static void solidTriColored(
            double x0, double y0, double z0, int rgb0,
            double x1, double y1, double z1, int rgb1,
            double x2, double y2, double z2, int rgb2) {
        if (buf == null) return;
        float shadeF = 0.85f + 0.15f * (float) Math.max(-1, Math.min(1, (cy - (y0+y1+y2)/3.0) * 0.05));
        int c0 = shade(rgb0, shadeF);
        int c1 = shade(rgb1, shadeF);
        int c2 = shade(rgb2, shadeF);
        // degenerate quad: v0,v1,v2,v2
        v(x0, y0, z0, c0);
        v(x1, y1, z1, c1);
        v(x2, y2, z2, c2);
        v(x2, y2, z2, c2);
    }

    /** Quad in world space (matches solidBox coordinate convention). */
    private static void quadWorld(double x1, double y1, double z1, double x2, double y2, double z2,
                                  double x3, double y3, double z3, double x4, double y4, double z4, int argb) {
        // reuse existing private quad which expects camera-relative or world? inspect below
        quad(x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4, argb);
    }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** A flat square on the horizontal plane y (outline + optional fill), e.g. a chunk footprint. */
    public static void square(double x1, double z1, double x2, double z2, double y, int line, int fill, int thickness) {
        if (buf == null) return;
        if ((fill >>> 24) != 0) quad(x1, y, z1, x2, y, z1, x2, y, z2, x1, y, z2, fill);
        double dx = (x1 + x2) / 2 - cx, dy = y - cy, dz = (z1 + z2) / 2 - cz;
        double dist = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double w = dist * pxPerUnit * Math.max(1.5, thickness * guiScale * 0.75);
        ribbon(x1, y, z1, x2, y, z1, 0, w, line);
        ribbon(x1, y, z2, x2, y, z2, 0, w, line);
        ribbon(x1, y, z1, x1, y, z2, 2, w, line);
        ribbon(x2, y, z1, x2, y, z2, 2, w, line);
    }

    /** An axis-aligned line on the horizontal plane y from (x1, z1) to (x2, z2), at the same constant on-screen thickness as {@link #square}. */
    public static void flatLine(double x1, double z1, double x2, double z2, double y, int argb, int thickness) {
        if (buf == null) return;
        double dx = (x1 + x2) / 2 - cx, dy = y - cy, dz = (z1 + z2) / 2 - cz;
        double dist = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double w = dist * pxPerUnit * Math.max(1.5, thickness * guiScale * 0.75);
        boolean alongX = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
        ribbon(Math.min(x1, x2), y, Math.min(z1, z2), Math.max(x1, x2), y, Math.max(z1, z2), alongX ? 0 : 2, w, argb);
    }

    /**
     * A flat ring (or a disc when inner = 0) lying on the horizontal plane y, centred on (x, z), drawn as 'segments'
     * quads with the given ARGB colour (alpha is honoured, so it can fade). Used by Jump Bubbles.
     */
    public static void ring(double x, double y, double z, double inner, double outer, int argb, int segments) {
        if (buf == null || outer <= 0 || (argb >>> 24) == 0) return;
        int n = Math.max(8, segments);
        double step = Math.PI * 2 / n;
        for (int i = 0; i < n; i++) {
            double a0 = i * step, a1 = (i + 1) * step;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            quad(x + c0 * inner, y, z + s0 * inner, x + c0 * outer, y, z + s0 * outer,
                 x + c1 * outer, y, z + s1 * outer, x + c1 * inner, y, z + s1 * inner, argb);
        }
    }

    /** A small flat dot (constant on-screen size) centred on (x, y, z). */
    public static void dot(double x, double y, double z, int argb) {
        if (buf == null) return;
        double dx = x - cx, dy = y - cy, dz = z - cz;
        double dist = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double h = dist * pxPerUnit * 3.0 * guiScale;
        quad(x - h, y, z - h, x + h, y, z - h, x + h, y, z + h, x - h, y, z + h, argb);
    }

    // ------------------------------------------------------------------ cosmetics: depth test, gradients, camera-facing shapes

    /** true = following draw calls are hidden by terrain (and drawn before the ESP); false = through walls like ESP. Always reset to false when done. */
    public static void setDepthTest(boolean on) {
        if (plainBuf == null) return;
        buf = on ? depthBuf : plainBuf;
    }

    /** A quad with a colour per corner (a, b, c, d around the face), so ribbons can fade along their length and across their width. */
    public static void gradientQuad(double ax, double ay, double az, int ca, double bx, double by, double bz, int cb,
                                    double cx2, double cy2, double cz2, int cc, double dx, double dy, double dz, int cd) {
        if (buf == null) return;
        v(ax, ay, az, ca); v(bx, by, bz, cb); v(cx2, cy2, cz2, cc); v(dx, dy, dz, cd);
    }

    /**
     * Flat shape that always faces the camera: a triangle fan from its centre over the unit polygon (x, y pairs, roughly -1..1),
     * scaled to 'size' blocks and turned by 'rot' radians. centerArgb / edgeArgb give a soft gradient from the middle outwards.
     */
    public static void billboardPoly(double x, double y, double z, double size, double rot, float[] poly, int centerArgb, int edgeArgb) {
        if (buf == null || poly.length < 6 || ((centerArgb | edgeArgb) >>> 24) == 0) return;
        double cs = Math.cos(rot) * size, sn = Math.sin(rot) * size;
        int n = poly.length / 2;
        double rx = CAM_RIGHT.x, ry = CAM_RIGHT.y, rz = CAM_RIGHT.z, ux = CAM_UP.x, uy = CAM_UP.y, uz = CAM_UP.z;
        double px0 = 0, py0 = 0, pz0 = 0;
        for (int i = 0; i <= n; i++) {
            float lx = poly[(i % n) * 2], ly = poly[(i % n) * 2 + 1];
            double qx = lx * cs - ly * sn, qy = lx * sn + ly * cs;                    // rotated in the camera plane
            double wx = x + rx * qx + ux * qy, wy = y + ry * qx + uy * qy, wz = z + rz * qx + uz * qy;
            if (i > 0) {
                v(x, y, z, centerArgb); v(px0, py0, pz0, edgeArgb); v(wx, wy, wz, edgeArgb); v(wx, wy, wz, edgeArgb);   // triangle as a degenerate quad
            }
            px0 = wx; py0 = wy; pz0 = wz;
        }
    }

    // ------------------------------------------------------------------ shader markers (glowing, frosted)

    /**
     * Flat square on the horizontal plane y with glowing edges at a constant on-screen width and a frosted fill
     * (fillPct 0..100 = how strong the fill is). argb is the edge colour; thickness 1..4.
     */
    public static void glowSquare(double x1, double z1, double x2, double z2, double y, int argb, int fillPct, int thickness) {
        if (sbuf == null) return;
        int c = ShaderUi.pack(argb, (Math.round(Math.max(0, Math.min(100, fillPct)) * 15f / 100f) << 2) | Math.max(0, Math.min(3, thickness - 1)));
        squareQuad(x1, y, z1, x2, y, z1, x2, y, z2, x1, y, z2, c);
    }

    /** Box with glowing edges on all six faces (see through walls), plus a faint frosted fill. */
    public static void glowBox(double x1, double y1, double z1, double x2, double y2, double z2, int argb, int fillPct, int thickness) {
        if (sbuf == null) return;
        int c = ShaderUi.pack(argb, (Math.round(Math.max(0, Math.min(100, fillPct)) * 15f / 100f) << 2) | Math.max(0, Math.min(3, thickness - 1)));
        squareQuad(x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, c);   // bottom
        squareQuad(x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, c);   // top
        squareQuad(x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, c);   // north
        squareQuad(x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, c);   // south
        squareQuad(x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, c);   // west
        squareQuad(x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, c);   // east
    }

    /** Round glowing dot (constant on-screen size) centred on (x, y, z). */
    public static void glowDot(double x, double y, double z, int argb) {
        if (sbuf == null) return;
        double dx = x - cx, dy = y - cy, dz = z - cz;
        double dist = Math.max(1.0, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double h = dist * pxPerUnit * 5.0 * guiScale;
        int c = ShaderUi.pack(argb, 0);
        // u,v in 2..3 tells the shader "this quad is a dot"
        sv(x - h, y, z - h, 2f, 2f, c); sv(x + h, y, z - h, 3f, 2f, c); sv(x + h, y, z + h, 3f, 3f, c); sv(x - h, y, z + h, 2f, 3f, c);
    }

    /** One quad whose UVs run 0..1 (corner order a, b, c, d around the face). */
    private static void squareQuad(double ax, double ay, double az, double bx, double by, double bz,
                                   double cx2, double cy2, double cz2, double dx, double dy, double dz, int argb) {
        sv(ax, ay, az, 0f, 0f, argb); sv(bx, by, bz, 1f, 0f, argb); sv(cx2, cy2, cz2, 1f, 1f, argb); sv(dx, dy, dz, 0f, 1f, argb);
    }

    /** One shader vertex: same camera-relative transform as v(), plus UV. */
    private static void sv(double x, double y, double z, float u, float v, int argb) {
        TMP.set((float) (x - cx), (float) (y - cy), (float) (z - cz));
        MAT.transformPosition(TMP);
        sbuf.addVertex(TMP.x, TMP.y, TMP.z).setUv(u, v).setColor(argb);
    }

    // ------------------------------------------------------------------ internals

    private static void edges(double x1, double y1, double z1, double x2, double y2, double z2, double w, int c) {
        double[] xs = {x1, x2}, ys = {y1, y2}, zs = {z1, z2};
        for (int a = 0; a < 2; a++)
            for (int b = 0; b < 2; b++) {
                ribbon(x1, ys[a], zs[b], x2, ys[a], zs[b], 0, w, c);   // 4 edges along X
                ribbon(xs[a], y1, zs[b], xs[a], y2, zs[b], 1, w, c);   // 4 edges along Y
                ribbon(xs[a], ys[b], z1, xs[a], ys[b], z2, 2, w, c);   // 4 edges along Z
            }
    }

    /** One box edge as two crossed quads (a "+" in cross-section), so it has width from every direction. */
    private static void ribbon(double ax, double ay, double az, double bx, double by, double bz, int axis, double w, int c) {
        double h = w * 0.5;
        if (axis == 0) { ax -= h; bx += h; } else if (axis == 1) { ay -= h; by += h; } else { az -= h; bz += h; }   // close the corners
        for (int k = 0; k < 2; k++) {
            int perp = (axis + 1 + k) % 3;
            double ox = perp == 0 ? h : 0, oy = perp == 1 ? h : 0, oz = perp == 2 ? h : 0;
            quad(ax - ox, ay - oy, az - oz, bx - ox, by - oy, bz - oz, bx + ox, by + oy, bz + oz, ax + ox, ay + oy, az + oz, c);
        }
    }

    private static void fillBox(double x1, double y1, double z1, double x2, double y2, double z2, int c) {
        quad(x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, c);   // bottom
        quad(x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, c);   // top
        quad(x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, c);   // north
        quad(x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, c);   // south
        quad(x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, c);   // west
        quad(x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, c);   // east
    }

    private static void quad(double ax, double ay, double az, double bx, double by, double bz,
                             double cx2, double cy2, double cz2, double dx, double dy, double dz, int argb) {
        v(ax, ay, az, argb); v(bx, by, bz, argb); v(cx2, cy2, cz2, argb); v(dx, dy, dz, argb);
    }

    /** One vertex: relative to the camera (keeps float precision high far from the origin), then through the stack matrix. */
    private static void v(double x, double y, double z, int argb) {
        TMP.set((float) (x - cx), (float) (y - cy), (float) (z - cz));
        MAT.transformPosition(TMP);
        buf.addVertex(TMP.x, TMP.y, TMP.z).setColor(argb);
    }
}
