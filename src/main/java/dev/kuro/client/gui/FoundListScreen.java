package dev.kuro.client.gui;

import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.Ui;
import dev.kuro.client.KuroClient;
import dev.kuro.client.module.Module;
import dev.kuro.client.module.impl.BaritoneControl;
import dev.kuro.client.module.impl.Waypoints;
import dev.kuro.client.util.Finds;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the base finders have found in one list: filter by source, sort by score or distance, save a waypoint
 * or send Baritone there with one click. Scroll with the mouse wheel; Esc closes.
 */
public class FoundListScreen extends Screen {
    private static final int ROW_H = 18, W = 460;
    private final Screen parent;
    private int sourceIdx = -1;          // -1 = all
    private boolean byDistance;
    private int scroll;
    private final List<Finds.Find> view = new ArrayList<>();
    private int top, left, listBottom;

    public FoundListScreen(Screen parent) {
        super(Component.literal("Found list"));
        this.parent = parent;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (dev.kuro.client.module.impl.Hud.menuBlur()) super.renderBackground(g, mx, my, delta);   // game's menu blur + darkening
        else g.fill(0, 0, width, height, 0x90000000);
    }

    private double dist(Finds.Find f) {
        if (minecraft == null || minecraft.player == null) return 0;
        double dx = f.x() + 0.5 - minecraft.player.getX(), dz = f.z() + 0.5 - minecraft.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void rebuild() {
        view.clear();
        List<String> sources = Finds.sources();
        String only = sourceIdx >= 0 && sourceIdx < sources.size() ? sources.get(sourceIdx) : null;
        for (Finds.Find f : Finds.all()) if (only == null || f.source().equals(only)) view.add(f);
        if (byDistance) view.sort((a, b) -> Double.compare(dist(a), dist(b)));
        else view.sort((a, b) -> Double.compare(b.score(), a.score()));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        dev.kuro.client.module.impl.Hud.applyTheme();
        rebuild();
        left = (width - W) / 2;
        top = 30;
        listBottom = height - 14;
        Ui.panel(g, left, 8, left + W, 26, Math.max(Ui.radius, 6), Theme.TITLE, Theme.PANEL_EDGE, Theme.GLOW, false);   // shader title bar
        SdfText.ui(g, "Found list  (" + view.size() + ")", left + 6, 14, Theme.TITLE_TEXT, false);

        List<String> sources = Finds.sources();
        String filter = sourceIdx >= 0 && sourceIdx < sources.size() ? sources.get(sourceIdx) : "All";
        String b1 = "Show: " + filter, b2 = "Sort: " + (byDistance ? "distance" : "score");
        int w1 = SdfText.w(b1) + 10, w2 = SdfText.w(b2) + 10;
        button(g, left + W - w1 - w2 - 8, 10, w1, 12, b1, mx, my);
        button(g, left + W - w2 - 4, 10, w2, 12, b2, mx, my);

        int visible = (listBottom - top) / ROW_H;
        scroll = Mth.clamp(scroll, 0, Math.max(0, view.size() - visible));
        if (view.isEmpty()) {
            Ui.round(g, left, top, left + W, top + ROW_H, Theme.ROW);
            SdfText.ui(g, "Nothing found yet. Turn on Sus Chunk Finder, Stash Finder, Geode ESP, Ancient Debris or Trail Tracker.", left + 6, top + 5, Theme.TEXT, false);
            return;
        }
        for (int i = 0; i < visible && i + scroll < view.size(); i++) {
            Finds.Find f = view.get(i + scroll);
            int y = top + i * ROW_H;
            boolean hover = mx >= left && mx < left + W && my >= y && my < y + ROW_H;
            Ui.corners(g, left, y, left + W, y + ROW_H - 1, 4, hover ? Theme.ROW_HOVER : Theme.ROW, true, true, true, true);
            String text = "[" + f.source() + "] " + f.label() + "   " + f.x() + ", " + f.y() + ", " + f.z() + "   " + (int) dist(f) + "m";
            SdfText.ui(g, SdfText.trim(text, W - 120), left + 5, y + 5, Theme.TEXT, false);
            button(g, left + W - 108, y + 2, 50, 13, "Waypoint", mx, my);
            button(g, left + W - 54, y + 2, 50, 13, "Go (Bar.)", mx, my);
        }
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        Ui.round(g, x, y, x + w, y + h, hover ? Theme.ON : Theme.TITLE);
        SdfText.ui(g, label, x + (w - SdfText.w(label)) / 2, y + (h - 8) / 2 + 1, hover ? Theme.ON_TEXT : Theme.TITLE_TEXT, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x(), my = click.y();
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        List<String> sources = Finds.sources();
        String filter = sourceIdx >= 0 && sourceIdx < sources.size() ? sources.get(sourceIdx) : "All";
        int w1 = SdfText.w("Show: " + filter) + 10, w2 = SdfText.w("Sort: " + (byDistance ? "distance" : "score")) + 10;
        if (my >= 10 && my < 22) {
            if (mx >= left + W - w1 - w2 - 8 && mx < left + W - w2 - 8) { sourceIdx = sourceIdx + 1 >= sources.size() ? -1 : sourceIdx + 1; scroll = 0; return true; }
            if (mx >= left + W - w2 - 4 && mx < left + W - 4) { byDistance = !byDistance; scroll = 0; return true; }
        }
        int row = ((int) my - top) / ROW_H;
        if (my >= top && row >= 0 && row + scroll < view.size()) {
            Finds.Find f = view.get(row + scroll);
            int y = top + row * ROW_H;
            if (my >= y + 2 && my < y + 15) {
                if (mx >= left + W - 108 && mx < left + W - 58) {
                    Waypoints w = Waypoints.get();
                    if (w != null) w.addAt(new BlockPos(f.x(), f.y(), f.z()), f.source() + " " + f.label());
                    return true;
                }
                if (mx >= left + W - 54 && mx < left + W - 4) {
                    for (Module m : KuroClient.modules.modules)
                        if (m instanceof BaritoneControl b) { onClose(); b.cmd("goto " + f.x() + " " + f.y() + " " + f.z()); }
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll -= (int) Math.signum(v) * 3;
        if (scroll < 0) scroll = 0;
        return true;
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
}
