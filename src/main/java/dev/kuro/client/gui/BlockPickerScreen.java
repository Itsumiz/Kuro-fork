package dev.kuro.client.gui;

import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.Ui;
import dev.kuro.client.module.impl.BlockEsp;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Search the block registry and tick the blocks Block ESP should highlight. */
public class BlockPickerScreen extends Screen {
    private static final int PW = 220, ROW = 13, MAX_ROWS = 14, LIST_Y = 58, MAX_LIST = 400;
    // per-block colour strip (selected rows only): rainbow strip + a small box that resets to the global colour
    private static final int STRIP_OFF = 128, STRIP_W = 66, RESET_OFF = 200, RESET_W = 14;

    private final Screen parent;
    private final BlockEsp esp;
    private final List<Block> shown = new ArrayList<>();
    private EditBox search;
    private int scroll;      // first visible row
    private Block dragging; // block whose colour strip is being dragged

    public BlockPickerScreen(Screen parent, BlockEsp esp) {
        super(Component.literal("Choose blocks"));
        this.parent = parent;
        this.esp = esp;
    }

    @Override
    protected void init() {
        search = new EditBox(font, width / 2 - PW / 2 + 4, 34, PW - 8, 16, Component.literal("Search"));
        search.setHint(Component.literal("Search blocks (e.g. ore, chest, spawner)"));
        search.setResponder(s -> refresh());
        addRenderableWidget(search);
        setInitialFocus(search);
        refresh();
    }

    private void refresh() {
        String q = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT).trim();
        shown.clear();
        for (Block b : esp.targets) if (matches(b, q)) shown.add(b);   // selected first
        for (Block b : BuiltInRegistries.BLOCK) {
            if (shown.size() >= MAX_LIST) break;
            if (b == Blocks.AIR || esp.targets.contains(b) || !matches(b, q)) continue;
            shown.add(b);
        }
        scroll = Math.max(0, Math.min(scroll, shown.size() - MAX_ROWS));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (shown.size() > MAX_ROWS) {
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(vertical) * 3, shown.size() - MAX_ROWS));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    private static boolean matches(Block b, String q) {
        return q.isEmpty() || BuiltInRegistries.BLOCK.getKey(b).getPath().contains(q);
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (dev.kuro.client.module.impl.Hud.menuBlur()) super.renderBackground(g, mx, my, delta);   // game's menu blur + darkening
        else g.fill(0, 0, width, height, 0x90000000);   // plain dim when blur is off
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        dev.kuro.client.module.impl.Hud.applyTheme();
        int px = width / 2 - PW / 2;
        int bottom = LIST_Y + MAX_ROWS * ROW + 22;
        Ui.panel(g, px, 12, px + PW, bottom, Math.max(Ui.radius, 6), Theme.PANEL, Theme.PANEL_EDGE, Theme.GLOW);   // shader window
        Ui.corners(g, px, 12, px + PW, 28, Math.max(Ui.radius, 6), Theme.TITLE, true, true, false, false);
        SdfText.ui(g, "Block ESP - choose blocks", px + 5, 16, Theme.TITLE_TEXT, false);
        super.render(g, mx, my, delta); // draws the search box

        int visible = Math.min(MAX_ROWS, shown.size() - scroll);
        for (int i = 0; i < visible; i++) {
            int y = LIST_Y + i * ROW;
            Block b = shown.get(scroll + i);
            boolean sel = esp.targets.contains(b);
            boolean hover = mx >= px && mx < px + PW && my >= y && my < y + ROW;
            if (sel) Ui.selected(g, px + 2, y, px + PW - 2, y + ROW - 1, 4, Theme.ON, BuiltInRegistries.BLOCK.getKey(b).getPath());
            else Ui.corners(g, px + 2, y, px + PW - 2, y + ROW - 1, 4, hover ? Theme.ROW_HOVER : Theme.ROW, true, true, true, true);
            String label = (sel ? "[x] " : "[ ] ") + BuiltInRegistries.BLOCK.getKey(b).getPath();
            SdfText.ui(g, label, px + 6, y + 3, Theme.TEXT, false);
            if (sel) drawColorStrip(g, b, px, y);
        }
        if (shown.size() > MAX_ROWS) {   // scrollbar
            int trackH = MAX_ROWS * ROW, thumbH = Math.max(10, trackH * MAX_ROWS / shown.size());
            int thumbY = LIST_Y + (trackH - thumbH) * scroll / (shown.size() - MAX_ROWS);
            Ui.corners(g, px + PW - 4, LIST_Y, px + PW - 2, LIST_Y + trackH, 1, Theme.SLIDER_BG, true, true, true, true);
            Ui.corners(g, px + PW - 4, thumbY, px + PW - 2, thumbY + thumbH, 1, Theme.SLIDER, true, true, true, true);
        }
        String tip = "Scroll for more. Drag a strip to colour a block, [x] resets it";
        SdfText.ui(g, tip, width / 2 - SdfText.w(tip) / 2, bottom + 4, 0xFFFFFFFF, true);
        int dy = LIST_Y + MAX_ROWS * ROW + 4;
        Ui.round(g, px + 2, dy, px + PW - 2, dy + 14, Theme.TITLE);
        String done = "Done";
        SdfText.ui(g, done, width / 2 - SdfText.w(done) / 2, dy + 3, Theme.TITLE_TEXT, false);
    }

    private void drawColorStrip(GuiGraphics g, Block b, int px, int y) {
        int sx = px + STRIP_OFF;
        boolean custom = esp.hues.containsKey(b);
        Ui.hueStrip(g, sx, y + 2, sx + STRIP_W, y + ROW - 2, 2);                        // rainbow (shader)
        if (!custom) Ui.corners(g, sx, y + 2, sx + STRIP_W, y + ROW - 2, 2, 0x70FFFFFF, true, true, true, true);   // washed out = default colour
        if (custom) {
            int m = sx + (int) (esp.hues.get(b) * STRIP_W);
            Ui.corners(g, m - 1, y + 1, m + 1, y + ROW - 1, 1, 0xFFFFFFFF, true, true, true, true);
        }
        // swatch showing the colour that is actually used right now (own or default)
        int cur = 0xFF000000 | dev.kuro.client.util.Look.rgb(esp.hueFor(b));
        int rx = px + RESET_OFF;
        Ui.corners(g, rx, y + 2, rx + RESET_W, y + ROW - 2, 3, cur, true, true, true, true);
        SdfText.ui(g, "x", rx + 4, y + 3, 0xFFFFFFFF, true);
    }

    private void setHue(Block b, double mx, int px) {
        float h = (float) Mth.clamp((mx - (px + STRIP_OFF)) / STRIP_W, 0.0, 0.999);
        esp.hues.put(b, h);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        int px = width / 2 - PW / 2;
        double mx = click.x(), my = click.y();
        if (click.button() == 0 && mx >= px && mx < px + PW) {
            int idx = (int) ((my - LIST_Y) / ROW);
            if (my >= LIST_Y && idx >= 0 && idx < MAX_ROWS && scroll + idx < shown.size()) {
                Block b = shown.get(scroll + idx);
                if (esp.targets.contains(b)) {
                    if (mx >= px + STRIP_OFF && mx < px + STRIP_OFF + STRIP_W) {   // pick a colour
                        dragging = b;
                        setHue(b, mx, px);
                        return true;
                    }
                    if (mx >= px + RESET_OFF && mx < px + RESET_OFF + RESET_W) {   // back to default
                        esp.hues.remove(b);
                        esp.colorChanged();
                        return true;
                    }
                }
                if (!esp.targets.remove(b)) esp.targets.add(b);
                esp.changed();
                refresh();
                return true;
            }
            int dy = LIST_Y + MAX_ROWS * ROW + 4;
            if (my >= dy && my < dy + 14) { onClose(); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (dragging != null) { setHue(dragging, click.x(), width / 2 - PW / 2); return true; }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragging != null) { dragging = null; esp.colorChanged(); return true; }
        return super.mouseReleased(click);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
