package dev.kuro.client.module.impl;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Finds;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ancient Debris (Base Finding): scans the chunks the server has sent you for ancient debris and outlines it through
 * walls, with tracers, distance labels and an entry in the Found List (source "Debris", so the Baritone button
 * "Nearest debris" can walk you there). "Rough area" (default) draws one fixed-size box (8x8x8) around each cluster
 * instead of exact blocks, for servers that shift ore positions.
 *
 * It only reads blocks the server actually sent. On a server with anti-xray, buried debris is replaced by netherrack
 * before it reaches your client, so only debris that is exposed to air or lava (or that you have uncovered) can show
 * up, and some anti-xray modes also send FAKE ore that disappears when you get close. Nothing here can see through that.
 */
public class AncientDebris extends Module {
    private static final int CHUNKS_PER_TICK = 8;
    private static final int MAX_SCAN = 4000;      // safety cap on debris blocks kept per pass

    private final Setting.Num range = add(new Setting.Num("Range (chunks)", 8, 1, 12, 1));
    private final Setting.Bool netherOnly = add(new Setting.Bool("Nether only", true));
    private final Setting.Bool rough = add(new Setting.Bool("Rough area", true));
    private final Setting.Num areaSize = add(new Setting.Num("Area size", 8, 4, 16, 1));
    private final Setting.Num areaHeight = add(new Setting.Num("Area height", 8, 2, 64, 2));
    private final Setting.Num minY = add(new Setting.Num("Min Y", 0, -64, 128, 1));
    private final Setting.Num maxY = add(new Setting.Num("Max Y", 127, -64, 128, 1));
    private final Setting.Bool exposedOnly = add(new Setting.Bool("Only exposed", false));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.07f).wheel());
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 2, 1, 3, 1));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool fill = add(new Setting.Bool("Fill", true));
    private final Setting.Num fillOpacity = add(new Setting.Num("Fill opacity", 60, 10, 200, 10));
    private final Setting.Bool tracers = add(new Setting.Bool("Tracers", true));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", true));
    private final Setting.Num maxShown = add(new Setting.Num("Max shown", 12, 1, 100, 1));
    private final Setting.Bool chatAlert = add(new Setting.Bool("Chat alert", true));

    /** A rough area: a fixed-size box centred on a cluster of debris, so a server that shifts ore positions can't mislead you. */
    private record Area(int x1, int y1, int z1, int x2, int y2, int z2, double cx, double cy, double cz, List<BlockPos> members) {}

    private final List<long[]> queue = new ArrayList<>();
    private int queuePos, timer = 1000;
    private boolean dirty = true;
    private List<BlockPos> building = new ArrayList<>();
    private volatile List<BlockPos> found = List.of();
    private volatile List<Area> areas = List.of();
    private final Set<Long> announced = new HashSet<>();
    private net.minecraft.resources.ResourceKey<Level> lastWorld;
    private final double[] pt = new double[2];

    public AncientDebris() { super("Ancient Debris", Category.BASE); }

    @Override protected void onEnable() { dirty = true; announced.clear(); }

    @Override
    protected void onDisable() {
        Finds.set("Debris", List.of());
        found = List.of();
        areas = List.of();
        queue.clear();
        queuePos = 0;
        announced.clear();
    }

    private boolean inDimension() {
        return !netherOnly.value || mc.level.dimension() == Level.NETHER;
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (lastWorld != mc.level.dimension()) {          // changed dimension: start clean
            lastWorld = mc.level.dimension();
            found = List.of();
            areas = List.of();
            Finds.set("Debris", List.of());
            announced.clear();
            dirty = true;
        }
        if (!inDimension()) {
            if (!found.isEmpty()) { found = List.of(); areas = List.of(); Finds.set("Debris", List.of()); }
            return;
        }
        if (dirty) { queue.clear(); queuePos = 0; timer = 1000; dirty = false; }
        if (queuePos >= queue.size()) {
            if (++timer < 30) return;                          // a new pass about every 1.5 s, spread over several ticks
            timer = 0;
            beginPass();
            if (queue.isEmpty()) return;
        }
        for (int n = 0; n < CHUNKS_PER_TICK && queuePos < queue.size(); n++) {
            long[] c = queue.get(queuePos++);
            LevelChunk chunk = mc.level.getChunkSource().getChunk((int) c[0], (int) c[1], false);
            if (chunk != null) scanChunk(chunk);
            if (building.size() >= MAX_SCAN) { queuePos = queue.size(); break; }
        }
        if (queuePos >= queue.size()) finishPass();
    }

    private void beginPass() {
        queue.clear();
        queuePos = 0;
        building = new ArrayList<>();
        ChunkPos cp = mc.player.chunkPosition();
        int rad = range.intValue();
        for (int dx = -rad; dx <= rad; dx++)
            for (int dz = -rad; dz <= rad; dz++) queue.add(new long[]{cp.x + dx, cp.z + dz});
        queue.sort((a, b) -> Long.compare(
                (a[0] - cp.x) * (a[0] - cp.x) + (a[1] - cp.z) * (a[1] - cp.z),
                (b[0] - cp.x) * (b[0] - cp.x) + (b[1] - cp.z) * (b[1] - cp.z)));
    }

    private static boolean isDebris(BlockState s) { return s.getBlock() == Blocks.ANCIENT_DEBRIS; }

    private void scanChunk(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int lo = Math.min(minY.intValue(), maxY.intValue()), hi = Math.max(minY.intValue(), maxY.intValue());
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(AncientDebris::isDebris)) continue;
            int baseY = (chunk.getMinSectionY() + i) << 4;
            if (baseY + 15 < lo || baseY > hi) continue;
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++) {
                    int wy = baseY + y;
                    if (wy < lo || wy > hi) continue;
                    for (int z = 0; z < 16; z++) {
                        if (!isDebris(sec.getBlockState(x, y, z))) continue;
                        BlockPos p = new BlockPos(baseX + x, wy, baseZ + z);
                        if (exposedOnly.value && !exposed(p)) continue;
                        building.add(p);
                    }
                }
        }
    }

    /** True when at least one side of the block touches air, lava/water or any non-solid block. */
    private boolean exposed(BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            BlockState s = mc.level.getBlockState(n);
            if (s.isAir() || !s.getFluidState().isEmpty() || s.getCollisionShape(mc.level, n).isEmpty()) return true;
        }
        return false;
    }

    private void finishPass() {
        List<BlockPos> out = building;
        building = new ArrayList<>();
        Vec3 p = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        out.sort((a, b) -> Double.compare(d2(a, p), d2(b, p)));
        found = out;
        List<Area> ar = rough.value ? cluster(out) : List.of();
        areas = ar;

        List<Finds.Find> pub = new ArrayList<>();
        if (rough.value) {
            for (int i = 0; i < ar.size() && i < 100; i++) {
                Area a = ar.get(i);
                pub.add(new Finds.Find("Debris", "Debris area, " + a.members().size() + " block" + (a.members().size() == 1 ? "" : "s"),
                        (int) Math.round(a.cx()), (int) Math.round(a.cy()), (int) Math.round(a.cz()), a.members().size()));
            }
        } else {
            for (int i = 0; i < out.size() && i < 200; i++) {
                BlockPos b = out.get(i);
                pub.add(new Finds.Find("Debris", "Ancient debris", b.getX(), b.getY(), b.getZ(), 1));
            }
        }
        Finds.set("Debris", pub);

        if (chatAlert.value) {
            int fresh = 0;
            BlockPos first = null;
            if (rough.value) {
                for (Area a : ar) {
                    boolean isNew = false;
                    for (BlockPos b : a.members()) if (announced.add(b.asLong())) isNew = true;
                    if (isNew) { fresh++; if (first == null) first = BlockPos.containing(a.cx(), a.cy(), a.cz()); }
                }
            } else {
                for (BlockPos b : out) if (announced.add(b.asLong())) { fresh++; if (first == null) first = b; }
            }
            if (fresh > 0 && first != null)
                CommandManager.say("Ancient debris: " + fresh + (rough.value ? " new area" + (fresh == 1 ? "" : "s") : " new")
                        + ", nearest " + (rough.value ? "around " : "") + first.getX() + " " + first.getY() + " " + first.getZ());
        }
    }

    /**
     * Greedy clustering, nearest debris first: every debris within half the area size (and half the area height)
     * of a cluster's first block joins it. The area box is then centred on the average of its blocks.
     */
    private List<Area> cluster(List<BlockPos> sorted) {
        double hx = areaSize.value / 2.0, hy = areaHeight.value / 2.0;
        int sx = areaSize.intValue(), sy = areaHeight.intValue();
        boolean[] used = new boolean[sorted.size()];
        List<Area> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (used[i]) continue;
            BlockPos seed = sorted.get(i);
            List<BlockPos> members = new ArrayList<>();
            double sumX = 0, sumY = 0, sumZ = 0;
            for (int j = i; j < sorted.size(); j++) {
                if (used[j]) continue;
                BlockPos b = sorted.get(j);
                if (Math.abs(b.getX() - seed.getX()) > hx || Math.abs(b.getZ() - seed.getZ()) > hx
                        || Math.abs(b.getY() - seed.getY()) > hy) continue;
                used[j] = true;
                members.add(b);
                sumX += b.getX() + 0.5; sumY += b.getY() + 0.5; sumZ += b.getZ() + 0.5;
            }
            int n = members.size();
            double cx = sumX / n, cy = sumY / n, cz = sumZ / n;
            int x1 = (int) Math.floor(cx - sx / 2.0), y1 = (int) Math.floor(cy - sy / 2.0), z1 = (int) Math.floor(cz - sx / 2.0);
            out.add(new Area(x1, y1, z1, x1 + sx, y1 + sy, z1 + sx, cx, cy, cz, members));
        }
        return out;
    }

    private static double d2(BlockPos b, Vec3 p) {
        double dx = b.getX() + 0.5 - p.x, dy = b.getY() + 0.5 - p.y, dz = b.getZ() + 0.5 - p.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Where to send Baritone: the centre of the nearest area (or the nearest block when Rough area is off), or null. */
    public BlockPos nearest() {
        if (mc.player == null || mc.level == null) return null;
        Vec3 p = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        if (rough.value) {
            Area best = null;
            double bd = Double.MAX_VALUE;
            for (Area a : areas) {
                if (!stillThere(a)) continue;
                double dx = a.cx() - p.x, dy = a.cy() - p.y, dz = a.cz() - p.z, d = dx * dx + dy * dy + dz * dz;
                if (d < bd) { bd = d; best = a; }
            }
            return best == null ? null : BlockPos.containing(best.cx(), best.cy(), best.cz());
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos b : found) {
            if (!isDebris(mc.level.getBlockState(b))) continue;
            double d = d2(b, p);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    /** An area stays up while at least one of its blocks is still debris. */
    private boolean stillThere(Area a) {
        for (BlockPos b : a.members()) if (isDebris(mc.level.getBlockState(b))) return true;
        return false;
    }

    @Override
    public void onWorld() {
        if (mc.level == null) return;
        int rgb = color.rgb(), line = 0xFF000000 | rgb, shade = fill.value ? (fillOpacity.intValue() << 24) | rgb : 0;
        int t = thickness.intValue(), drawn = 0, limit = maxShown.intValue();
        if (rough.value) {
            for (Area a : areas) {
                if (drawn >= limit) break;
                if (!stillThere(a)) continue;
                drawn++;
                WorldGfx.box(a.x1(), a.y1(), a.z1(), a.x1(), a.y1(), a.z2(), line, shade, t, glow.value);
            }
            return;
        }
        for (BlockPos p : found) {
            if (drawn >= limit) break;
            if (!isDebris(mc.level.getBlockState(p))) continue;     // mined since the last pass
            drawn++;
            WorldGfx.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, line, shade, t, glow.value);
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        if (mc.level == null || (!tracers.value && !labels.value)) return;
        int line = 0xFF000000 | color.rgb();
        Vec3 c = Projector.camPos;
        int drawn = 0, limit = maxShown.intValue();
        if (rough.value) {
            for (Area a : areas) {
                if (drawn >= limit) break;
                if (!stillThere(a)) continue;
                drawn++;
                if (tracers.value) {
                    Projector.projectOrPoint(a.cx(), a.cy(), a.cz(), pt);
                    Gfx.line(g, Projector.width() / 2.0, Projector.height() / 2.0, pt[0], pt[1], 1, Look.withAlpha(line, 0xB4));
                }
                if (labels.value && Projector.project(a.cx(), a.y1() + 0.8, a.cz(), pt)) {
                    double dx = a.cx() - c.x, dy = a.cy() - c.y, dz = a.cz() - c.z;
                    int n = a.members().size();
                    String text = "Debris area ~" + n + "  " + Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + "m";
                    Look.tag(g, mc.font, text, (int) pt[0], (int) pt[1] - 7, line);
                }
            }
            return;
        }
        for (BlockPos p : found) {
            if (drawn >= limit) break;
            if (!isDebris(mc.level.getBlockState(p))) continue;
            drawn++;
            double cx = p.getX() + 0.5, cy = p.getY() + 0.5, cz = p.getZ() + 0.5;
            if (tracers.value) {
                Projector.projectOrPoint(cx, cy, cz, pt);
                Gfx.line(g, Projector.width() / 2.0, Projector.height() / 2.0, pt[0], pt[1], 1, Look.withAlpha(line, 0xB4));
            }
            if (labels.value && Projector.project(cx, p.getY() + 1.6, cz, pt)) {
                double dx = cx - c.x, dy = cy - c.y, dz = cz - c.z;
                String text = "Debris  Y" + p.getY() + "  " + Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) + "m";
                Look.tag(g, mc.font, text, (int) pt[0], (int) pt[1] - 7, line);
            }
        }
    }
}
