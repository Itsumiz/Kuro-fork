package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Looks through the loaded chunks for storage blocks (chests, trapped chests, barrels, shulker boxes)
 * packed close together. A natural dungeon has 1-2 chests, so a group of {Min chests} or more inside
 * {Radius} blocks is reported in your chat (client side only, nobody else sees it).
 */
public class StashFinder extends Module {
    private final Setting.Num minChests = add(new Setting.Num("Min chests", 3, 2, 20, 1));
    private final Setting.Num radius = add(new Setting.Num("Radius", 12, 4, 32, 1));
    private final Setting.Num range = add(new Setting.Num("Range (chunks)", 6, 2, 12, 1));
    private final Setting.Bool barrels = add(new Setting.Bool("Barrels / shulkers", true));
    private final Setting.Bool ignoreDungeons = add(new Setting.Bool("Ignore dungeons", true));

    private final Setting.Bool highlight = add(new Setting.Bool("Highlight", true));
    private final Setting.Num highlightRange = add(new Setting.Num("Highlight range", 200, 50, 1000, 50));
    private final Setting.Num maxBoxes = add(new Setting.Num("Max boxes", 300, 25, 1500, 25));
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 1, 1, 3, 1));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Bool fill = add(new Setting.Bool("Fill", true));
    private final Setting.Num fillOpacity = add(new Setting.Num("Fill opacity", 40, 10, 200, 10));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.08f).wheel());
    private final Setting.Bool merge = add(new Setting.Bool("One box per stash", false));
    private final Setting.Bool labels = add(new Setting.Bool("Labels", true));

    private final List<dev.kuro.client.util.Finds.Find> stashFinds = new ArrayList<>();
    private final Set<BlockPos> reported = new HashSet<>();
    /** One stash with its centre and bounding box worked out once, so drawing a frame is just a loop over numbers. */
    private record Stash(List<BlockPos> blocks, double cx, double cy, double cz, int x1, int y1, int z1, int x2, int y2, int z2) {
        static Stash of(List<BlockPos> g) {
            int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE;
            for (BlockPos p : g) {
                x1 = Math.min(x1, p.getX()); y1 = Math.min(y1, p.getY()); z1 = Math.min(z1, p.getZ());
                x2 = Math.max(x2, p.getX()); y2 = Math.max(y2, p.getY()); z2 = Math.max(z2, p.getZ());
            }
            return new Stash(g, (x1 + x2 + 1) / 2.0, (y1 + y2 + 1) / 2.0, (z1 + z2 + 1) / 2.0, x1, y1, z1, x2, y2, z2);
        }
    }

    private final List<Stash> groups = new ArrayList<>();      // every stash found, for the highlight
    private final double[] o = new double[2];
    private Level lastWorld;
    private int timer, sortTimer;

    public StashFinder() { super("Stash Finder", Category.BASE); }

    @Override protected void onEnable() { reported.clear(); timer = 1000; }
    @Override protected void onDisable() { reported.clear(); groups.clear(); stashFinds.clear(); dev.kuro.client.util.Finds.set("Stash", stashFinds); }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (mc.level != lastWorld) { lastWorld = mc.level; reported.clear(); groups.clear(); stashFinds.clear(); dev.kuro.client.util.Finds.set("Stash", stashFinds); }   // new world / dimension
        if (++sortTimer >= 10 && groups.size() > 1) {       // nearest stash first, so the box limit keeps the close ones
            sortTimer = 0;
            groups.sort(Comparator.comparingDouble(st -> mc.player.distanceToSqr(st.cx(), st.cy(), st.cz())));
        }
        if (++timer < 40) return;                                                 // scan every ~2 seconds
        timer = 0;

        List<BlockPos> stores = new ArrayList<>();
        List<BlockPos> spawners = new ArrayList<>();
        ChunkPos cp = mc.player.chunkPosition();
        int rad = range.intValue();
        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cp.x + dx, cp.z + dz, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (be instanceof SpawnerBlockEntity || be instanceof net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity
                            || be instanceof net.minecraft.world.level.block.entity.vault.VaultBlockEntity) { spawners.add(pos); continue; }
                    boolean store = be instanceof ChestBlockEntity
                            || (barrels.value && (be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity));
                    if (!store) continue;
                    BlockState st = be.getBlockState();
                    // a double chest is two block entities: count it once
                    if (st.hasProperty(ChestBlock.TYPE) && st.getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue;
                    stores.add(pos);
                    if (stores.size() >= 3000) break;
                }
            }
        }
        if (stores.size() < minChests.intValue()) return;

        double r2 = radius.value * radius.value;
        Set<BlockPos> used = new HashSet<>();
        for (BlockPos c : stores) {
            if (used.contains(c)) continue;
            List<BlockPos> group = new ArrayList<>();
            for (BlockPos o : stores) if (c.distSqr(o) <= r2) group.add(o);
            if (group.size() < minChests.intValue()) continue;
            used.addAll(group);

            boolean seen = false;
            for (BlockPos g : group) if (reported.contains(g)) { seen = true; break; }
            if (seen) continue;

            if (ignoreDungeons.value && nearSpawner(group, spawners)) continue;
            reported.addAll(group);
            groups.add(Stash.of(group));
            announce(group);
        }
    }

    /**
     * World pass (same idea as Block ESP): every box is queued in real world coordinates and WorldGfx draws them all
     * in ONE GPU call through the game's own projection, so they stay glued to their chests and cost almost nothing.
     */
    @Override
    public void onWorld() {
        if (!highlight.value || groups.isEmpty()) return;
        Vec3 c = Projector.camPos;
        double max2 = highlightRange.value * highlightRange.value;
        int t = thickness.intValue(), limit = maxBoxes.intValue(), drawn = 0;
        int rgb = color.rgb();
        int line = 0xFF000000 | rgb, fillC = fill.value ? (fillOpacity.intValue() << 24) | rgb : 0;
        boolean glowOn = glow.value, one = merge.value;
        for (Stash st : groups) {
            if (drawn >= limit) break;
            double dx = st.cx() - c.x, dy = st.cy() - c.y, dz = st.cz() - c.z;
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            if (one) {
                drawn++;
                WorldGfx.box(st.x1(), st.y1(), st.z1(), st.x1() + 1, st.y1() + 1, st.z2() + 1, line, fillC, t, glowOn);
                continue;
            }
            for (BlockPos p : st.blocks()) {
                if (drawn >= limit) break;
                if (Projector.depth(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) < -1.0) continue;   // behind us
                drawn++;
                WorldGfx.box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, line, fillC, t, glowOn && drawn <= 40);
            }
        }
    }

    /** HUD pass: only the 2D labels (one small tag per stash). The boxes themselves are in onWorld(). */
    @Override
    public void onHud(GuiGraphics g) {
        if (!highlight.value || !labels.value || groups.isEmpty()) return;
        double max2 = highlightRange.value * highlightRange.value;
        int accent = 0xFF000000 | color.rgb(), shown = 0;
        for (Stash st : groups) {
            if (shown >= 12) break;
            double dx = st.cx() - Projector.camPos.x, dy = st.cy() - Projector.camPos.y, dz = st.cz() - Projector.camPos.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > max2) continue;
            if (!Projector.project(st.cx(), st.y1() + 2.3, st.cz(), o)) continue;
            shown++;
            Look.tagClamped(g, mc.font, "STASH x" + st.blocks().size() + "  " + Math.round(Math.sqrt(d2)) + "m",
                    (int) o[0], (int) o[1] - 7, accent);
        }
    }

    private boolean nearSpawner(List<BlockPos> group, List<BlockPos> spawners) {
        double r2 = (radius.value / 2 + 6) * (radius.value / 2 + 6);
        for (BlockPos s : spawners)
            for (BlockPos g : group) if (s.distSqr(g) <= r2) return true;
        return false;
    }

    private void announce(List<BlockPos> group) {
        double sx = 0, sy = 0, sz = 0;
        for (BlockPos p : group) { sx += p.getX(); sy += p.getY(); sz += p.getZ(); }
        stashFinds.add(new dev.kuro.client.util.Finds.Find("Stash", "Stash x" + group.size(),
                (int) (sx / group.size()), (int) (sy / group.size()), (int) (sz / group.size()), group.size()));
        dev.kuro.client.util.Finds.set("Stash", stashFinds);
        mc.player.displayClientMessage(Component.literal("[Stash Finder] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(group.size() + " storage blocks within " + radius.intValue() + " blocks:")
                        .withStyle(ChatFormatting.YELLOW)), false);
        int shown = 0;
        for (BlockPos p : group) {
            if (shown++ >= 10) break;
            mc.player.displayClientMessage(Component.literal("  " + p.getX() + " " + p.getY() + " " + p.getZ()).withStyle(ChatFormatting.WHITE), false);
        }
        if (group.size() > 10) {
            mc.player.displayClientMessage(Component.literal("  ... and " + (group.size() - 10) + " more").withStyle(ChatFormatting.GRAY), false);
        }
    }
}
