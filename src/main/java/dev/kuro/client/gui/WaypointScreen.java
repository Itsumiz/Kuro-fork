package dev.kuro.client.gui;

import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.Ui;
import dev.kuro.client.module.impl.Waypoints;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Manage the waypoints of the current world: add one with a name, click a name to rename it,
 * drag a colour strip to recolour it, click the colour box to reset it to white, x deletes.
 */
public class WaypointScreen extends Screen {
    private static final int PW = 232, ROW = 16, MAX_ROWS = 9, LIST_Y = 58;
    private static final int NAME_X = 6, NAME_W = 96;
    private static final int STRIP_OFF = 106, STRIP_W = 76;
    private static final int RESET_OFF = 186, DEL_OFF = 204, BOX_W = 14;
    private static final int ADD_OFF = 166, ADD_W = 62;

    private final Screen parent;
    private final Waypoints wps;
    private EditBox nameField;   // name for a NEW waypoint
    private EditBox editField;   // inline rename of an existing one
    private Waypoints.Wp editing;
    private Waypoints.Wp dragging;       // waypoint whose colour strip is being dragged
    private int scroll;

    public WaypointScreen(Screen parent, Waypoints wps) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
        this.wps = wps;
    }

    @Override
    protected void init() {
        int px = width / 2 - PW / 2;
        nameField = new EditBox(font, px + 4, 34, ADD_OFF - 8, 16, Component.literal("Name"));
        nameField.setHint(Component.literal("Name for a new waypoint"));
        nameField.setMaxLength(32);
        addRenderableWidget(nameField);

        editField = new EditBox(font, px + NAME_X, LIST_Y, NAME_W, ROW, Component.literal("Rename"));
        editField.setMaxLength(32);
        editField.visible = false;
        addRenderableWidget(editField);

        setInitialFocus(nameField);
    }

    private List<Waypoints.Wp> list() { return wps.here(); }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, list().size() - MAX_ROWS));
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (dev.kuro.client.module.impl.Hud.menuBlur()) super.renderBackground(g, mx, my, delta);   // game's menu blur + darkening
        else g.fill(0, 0, width, height, 0x90000000);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        dev.kuro.client.module.impl.Hud.applyTheme();
        List<Waypoints.Wp> list = list();
        clampScroll();
        int px = width / 2 - PW / 2;
        int bottom = LIST_Y + MAX_ROWS * ROW + 22;

        Ui.panel(g, px, 12, px + PW, bottom, Math.max(Ui.radius, 6), Theme.PANEL, Theme.PANEL_EDGE, Theme.GLOW);   // shader window
        Ui.corners(g, px, 12, px + PW, 28, Math.max(Ui.radius, 6), Theme.TITLE, true, true, false, false);
        SdfText.ui(g, "Waypoints (this world)", px + 5, 16, Theme.TITLE_TEXT, false);

        boolean addHover = mx >= px + ADD_OFF && mx < px + ADD_OFF + ADD_W && my >= 34 && my < 50;
        Ui.round(g, px + ADD_OFF, 34, px + ADD_OFF + ADD_W, 50, addHover ? Theme.ROW_HOVER : Theme.TITLE);
        SdfText.ui(g, "Add here", px + ADD_OFF + (ADD_W - SdfText.w("Add here")) / 2, 38, Theme.TITLE_TEXT, false);

        Waypoints.Wp hovered = null;
        int visible = Math.min(MAX_ROWS, list.size() - scroll);
        for (int i = 0; i < visible; i++) {
            int y = LIST_Y + i * ROW;
            Waypoints.Wp w = list.get(scroll + i);
            boolean hover = mx >= px && mx < px + PW && my >= y && my < y + ROW;
            if (hover) hovered = w;
            Ui.corners(g, px + 2, y, px + PW - 2, y + ROW - 1, 4, w == editing ? Theme.SETTING : hover ? Theme.ROW_HOVER : Theme.ROW, true, true, true, true);
            if (w != editing) SdfText.ui(g, fit(w.name, NAME_W - 2), px + NAME_X, y + 4, Theme.TEXT, false);
            drawStrip(g, w, px, y);

            int rx = px + RESET_OFF;                                   // colour in use right now; click = back to white
            Ui.corners(g, rx - 1, y + 1, rx + BOX_W + 1, y + ROW - 1, 4, Theme.BORDER, true, true, true, true);
            Ui.corners(g, rx, y + 2, rx + BOX_W, y + ROW - 2, 3, 0xFF000000 | w.rgb(), true, true, true, true);

            int dx = px + DEL_OFF;                                     // delete
            Ui.corners(g, dx, y + 2, dx + BOX_W, y + ROW - 2, 3, 0xFFC0392B, true, true, true, true);
            SdfText.ui(g, "x", dx + 4, y + 4, 0xFFFFFFFF, false);
        }
        if (list.isEmpty()) {
            SdfText.ui(g, "No waypoints in this world yet.", px + 8, LIST_Y + 6, Theme.TEXT, false);
        }
        if (list.size() > MAX_ROWS) {                                  // scrollbar
            int trackH = MAX_ROWS * ROW, thumbH = Math.max(10, trackH * MAX_ROWS / list.size());
            int thumbY = LIST_Y + (trackH - thumbH) * scroll / (list.size() - MAX_ROWS);
            Ui.corners(g, px + PW - 4, LIST_Y, px + PW - 2, LIST_Y + trackH, 1, Theme.SLIDER_BG, true, true, true, true);
            Ui.corners(g, px + PW - 4, thumbY, px + PW - 2, thumbY + thumbH, 1, Theme.SLIDER, true, true, true, true);
        }

        super.render(g, mx, my, delta);                                // text fields draw on top of the rows

        int dy = LIST_Y + MAX_ROWS * ROW + 4;
        Ui.round(g, px + 2, dy, px + PW - 2, dy + 14, Theme.TITLE);
        SdfText.ui(g, "Done", width / 2 - SdfText.w("Done") / 2, dy + 3, Theme.TITLE_TEXT, false);

        String tip = hovered != null
                ? hovered.name + "  " + hovered.x + ", " + hovered.y + ", " + hovered.z
                : "Click a name to rename. Drag a strip to recolour. Box = white, x = delete";
        SdfText.ui(g, tip, width / 2 - SdfText.w(tip) / 2, bottom + 4, 0xFFFFFFFF, true);
    }

    private String fit(String s, int maxW) {
        if (SdfText.w(s) <= maxW) return s;
        while (s.length() > 1 && SdfText.w(s + "..") > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private void drawStrip(GuiGraphics g, Waypoints.Wp w, int px, int y) {
        int sx = px + STRIP_OFF;
        boolean custom = w.hue >= 0;
        Ui.hueStrip(g, sx, y + 2, sx + STRIP_W, y + ROW - 2, 2);                        // rainbow (shader)
        if (!custom) Ui.corners(g, sx, y + 2, sx + STRIP_W, y + ROW - 2, 2, 0x70FFFFFF, true, true, true, true);   // washed out = default colour
        if (custom) {
            int m = sx + (int) (w.hue * STRIP_W);
            Ui.corners(g, m - 1, y + 1, m + 1, y + ROW - 1, 1, 0xFFFFFFFF, true, true, true, true);
        }
    }

    private void setHue(Waypoints.Wp w, double mx) {
        int px = width / 2 - PW / 2;
        w.hue = (float) Mth.clamp((mx - (px + STRIP_OFF)) / STRIP_W, 0.0, 0.999);
    }

    // ---------------- editing ----------------

    private void startEdit(Waypoints.Wp w, int x, int y) {
        commit();
        editing = w;
        editField.setValue(w.name);
        editField.setRectangle(x, y, NAME_W, ROW);
        editField.setWidth(NAME_W);
        
        editField.visible = true;
        setFocused(editField);
    }

    private void commit() {
        if (editing == null) return;
        wps.rename(editing, editField.getValue());
        editing = null;
        editField.visible = false;
        editField.setFocused(false);
    }

    private void addFromField() {
        String n = nameField.getValue().trim();
        wps.addNamed(n.isEmpty() ? null : n);
        nameField.setValue("");
        scroll = Math.max(0, list().size() - MAX_ROWS);               // show the new one
    }

    // ---------------- input ----------------

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (list().size() > MAX_ROWS) {
            commit();
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(vertical) * 2, list().size() - MAX_ROWS));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        int px = width / 2 - PW / 2;
        double mx = click.x(), my = click.y();
        if (editing != null && !editField.isMouseOver(mx, my)) commit();   // clicking elsewhere finishes a rename

        if (click.button() == 0) {
            if (mx >= px + ADD_OFF && mx < px + ADD_OFF + ADD_W && my >= 34 && my < 50) { addFromField(); return true; }

            List<Waypoints.Wp> list = list();
            int idx = (int) ((my - LIST_Y) / ROW);
            if (my >= LIST_Y && idx >= 0 && idx < MAX_ROWS && scroll + idx < list.size() && mx >= px && mx < px + PW) {
                Waypoints.Wp w = list.get(scroll + idx);
                int y = LIST_Y + idx * ROW;
                if (mx >= px + STRIP_OFF && mx < px + STRIP_OFF + STRIP_W) { dragging = w; setHue(w, mx); return true; }
                if (mx >= px + RESET_OFF && mx < px + RESET_OFF + BOX_W) { w.hue = -1f; wps.save(); return true; }
                if (mx >= px + DEL_OFF && mx < px + DEL_OFF + BOX_W) { wps.remove(w); clampScroll(); return true; }
                if (mx >= px + NAME_X - 2 && mx < px + STRIP_OFF - 2) { startEdit(w, px + NAME_X, y); return true; }
            }
            int dy = LIST_Y + MAX_ROWS * ROW + 4;
            if (mx >= px && mx < px + PW && my >= dy && my < dy + 14) { onClose(); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (dragging != null) { setHue(dragging, click.x()); return true; }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragging != null) { dragging = null; wps.save(); return true; }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.input();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (editing != null) { commit(); return true; }
            if (nameField.isFocused()) { addFromField(); return true; }
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && editing != null) { commit(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public void onClose() {
        commit();
        wps.save();
        minecraft.setScreen(parent);
    }
}
