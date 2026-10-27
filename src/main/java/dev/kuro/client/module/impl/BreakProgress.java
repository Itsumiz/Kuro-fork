package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.WorldGfx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Break Progress: shows how far you are through the block you are mining. The block fills up from the bottom while
 * you hold attack (red -> yellow -> green), with an optional bar and percentage on screen. The progress is worked
 * out from the same per-tick mining speed the game uses (tool, enchants, effects, water, air), so it matches what
 * you feel; it resets when you look at another block or let go.
 */
public class BreakProgress extends Module {
    private static final String[] STYLES = {"Fill box", "Outline", "Bar only"};

    private final Setting.Num style = add(new Setting.Num("Style", 0, 0, STYLES.length - 1, 1).labelled(STYLES));
    private final Setting.Bool bar = add(new Setting.Bool("Bar", true));
    private final Setting.Bool percent = add(new Setting.Bool("Percentage", true));
    private final Setting.Bool byProgress = add(new Setting.Bool("Color by progress", true));
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.55f));
    private final Setting.Num thickness = add(new Setting.Num("Thickness", 2, 1, 3, 1));
    private final Setting.Bool glow = add(new Setting.Bool("Glow", true));
    private final Setting.Num fillOpacity = add(new Setting.Num("Fill opacity", 90, 10, 200, 10));
    private final Setting.Bool throughWalls = add(new Setting.Bool("Through walls", false));

    private BlockPos pos;
    private float progress, perTick;
    private float lastProgress;

    public BreakProgress() { super("Break Progress", Category.RENDER); }

    @Override protected void onEnable() { reset(); }
    @Override protected void onDisable() { reset(); }

    private void reset() { pos = null; progress = 0f; lastProgress = 0f; perTick = 0f; }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null || mc.screen != null) { reset(); return; }
        boolean attacking = mc.options != null && mc.options.keyAttack != null && mc.options.keyAttack.isDown();
        if (!attacking || mc.player.getAbilities().instabuild || !(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) {
            reset();
            return;
        }
        BlockPos p = bhr.getBlockPos();
        BlockState st = mc.level.getBlockState(p);
        if (st.isAir()) { reset(); return; }
        if (!p.equals(pos)) { pos = p; progress = 0f; lastProgress = 0f; }
        lastProgress = progress;
        perTick = st.getDestroyProgress(mc.player, mc.level, p);    // fraction of the block mined each tick (vanilla formula)
        progress = Math.min(1f, progress + perTick);
    }

    /** Progress 0..1, smoothed between ticks. */
    private float shown(float partial) {
        return Mth.clamp(Mth.lerp(partial, lastProgress, progress), 0f, 1f);
    }

    private int rgbFor(float f) {
        if (!byProgress.value) return Look.rgb(color.hue);
        return Look.rgb(Mth.lerp(Mth.clamp(f, 0f, 1f), 0.0f, 0.33f));    // red -> yellow -> green
    }

    @Override
    public void onWorld() {
        if (pos == null || mc.level == null || !WorldGfx.active() || style.intValue() == 2) return;
        float f = shown(Projector.tickProgress);
        if (f <= 0.001f) return;
        int rgb = rgbFor(f);
        BlockState st = mc.level.getBlockState(pos);
        VoxelShape shape = st.getShape(mc.level, pos);
        double x1 = pos.getX(), y1 = pos.getY(), z1 = pos.getZ(), x2 = x1 + 1, y2 = y1 + 1, z2 = z1 + 1;
        if (!shape.isEmpty()) {
            x1 = pos.getX() + shape.min(net.minecraft.core.Direction.Axis.X); x2 = pos.getX() + shape.max(net.minecraft.core.Direction.Axis.X);
            y1 = pos.getY() + shape.min(net.minecraft.core.Direction.Axis.Y); y2 = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
            z1 = pos.getZ() + shape.min(net.minecraft.core.Direction.Axis.Z); z2 = pos.getZ() + shape.max(net.minecraft.core.Direction.Axis.Z);
        }
        double e = 0.004;                                   // sit just outside the block so it doesn't z-fight
        x1 -= e; y1 -= e; z1 -= e; x2 += e; y2 += e; z2 += e;
        WorldGfx.setDepthTest(!throughWalls.value);
        try {
            if (style.intValue() == 0) {
                double top = y1 + (y2 - y1) * f;           // fills upwards
                WorldGfx.box(x1, y1, z1, x2, top, z2, 0x00000000, (fillOpacity.intValue() << 24) | rgb, 1, false);
            }
            WorldGfx.box(x1, y1, z1, x2, y2, z2, 0xFF000000 | rgb, 0, thickness.intValue(), glow.value);
        } finally {
            WorldGfx.setDepthTest(false);
        }
    }

    @Override
    public void onHud(GuiGraphics g) {
        if (pos == null || (!bar.value && !percent.value && style.intValue() != 2)) return;
        float f = shown(Projector.tickProgress);
        if (f <= 0.001f) return;
        int col = 0xFF000000 | rgbFor(f);
        int cx = Projector.width() / 2, cy = Projector.height() / 2 + 18;       // just under the crosshair
        if (bar.value || style.intValue() == 2) Look.bar(g, cx - 30, cy, 60, 4, f, col);
        if (percent.value) Look.tag(g, mc.font, Math.round(f * 100) + "%", cx, cy + 10, col);
    }
}
