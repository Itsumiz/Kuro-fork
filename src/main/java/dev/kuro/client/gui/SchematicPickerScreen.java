package dev.kuro.client.gui;

import dev.kuro.client.module.impl.AutoBuild;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lists every schematic in the schematics folder. Click one to select it for Auto Build.
 * "Open folder" opens the folder in your file explorer so you can drop new files in; "Refresh" rescans it.
 * Mouse wheel scrolls, Esc closes.
 */
public class SchematicPickerScreen extends Screen {
    private static final int ROW_H = 18, W = 420;
    private final Screen parent;
    private final AutoBuild module;
    private List<String> files = new ArrayList<>();
    private int scroll, top, left, listBottom, visibleRows;

    public SchematicPickerScreen(Screen parent, AutoBuild module) {
        super(Component.literal("Schematics"));
        this.parent = parent;
        this.module = module;
        files = AutoBuild.listSchematics();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (dev.kuro.client.module.impl.Hud.menuBlur()) super.renderBackground(g, mx, my, delta);
        else g.fill(0, 0, width, height, 0x90000000);
    }

    private int openW() { return SdfText.w("Open folder") + 10; }
    private int refreshW() { return SdfText.w("Refresh") + 10; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        dev.kuro.client.module.impl.Hud.applyTheme();
        left = (width - W) / 2;
        top = 48;
        listBottom = height - 14;
        Ui.panel(g, left, 8, left + W, 26, Math.max(Ui.radius, 6), Theme.TITLE, Theme.PANEL_EDGE, Theme.GLOW, false);
        SdfText.ui(g, "Schematics  (" + files.size() + ")", left + 6, 14, Theme.TITLE_TEXT, false);
        button(g, left + W - openW() - refreshW() - 8, 10, openW(), 12, "Open folder", mx, my);
        button(g, left + W - refreshW() - 4, 10, refreshW(), 12, "Refresh", mx, my);

        String sel = module.selectedFile();
        SdfText.ui(g, SdfText.trim("Selected: " + (sel == null ? "(none)" : sel) + "   |   folder: " + AutoBuild.folder(), W - 4),
                left + 2, 33, Theme.TEXT, false);

        int visible = Math.max(1, (listBottom - top) / ROW_H);
        visibleRows = visible;
        scroll = Mth.clamp(scroll, 0, Math.max(0, files.size() - visible));
        if (files.isEmpty()) {
            Ui.round(g, left, top, left + W, top + ROW_H, Theme.ROW);
            SdfText.ui(g, "No schematics yet. Click \"Open folder\", drop .schem / .litematic / .schematic files in, then Refresh.",
                    left + 6, top + 5, Theme.TEXT, false);
            return;
        }
        for (int i = 0; i < visible && i + scroll < files.size(); i++) {
            String f = files.get(i + scroll);
            int y = top + i * ROW_H;
            boolean hover = mx >= left && mx < left + W && my >= y && my < y + ROW_H - 1;
            boolean chosen = sel != null && sel.equalsIgnoreCase(f);
            Ui.corners(g, left, y, left + W, y + ROW_H - 1, 4, chosen ? Theme.ON : hover ? Theme.ROW_HOVER : Theme.ROW, true, true, true, true);
            SdfText.ui(g, SdfText.trim(f, W - 14), left + 6, y + 5, chosen ? Theme.ON_TEXT : Theme.TEXT, false);
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
        if (my >= 10 && my < 22) {
            if (mx >= left + W - openW() - refreshW() - 8 && mx < left + W - refreshW() - 8) { AutoBuild.openFolder(); return true; }
            if (mx >= left + W - refreshW() - 4 && mx < left + W - 4) { files = AutoBuild.listSchematics(); scroll = 0; return true; }
        }
        if (my >= top && mx >= left && mx < left + W) {
            int row = ((int) my - top) / ROW_H + scroll;
            if (row >= scroll && row < scroll + visibleRows && row < files.size()) { module.chooseFile(files.get(row)); return true; }
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
