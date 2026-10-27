package dev.kuro.client.gui;

import dev.kuro.client.util.SdfText;
import dev.kuro.client.KuroClient;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Ui;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Vape-style draggable category windows (no logo). Module toggles and dropdown settings.
 * Left click = toggle / use, right click = open settings (or collapse a window),
 * middle click = bind a key, drag a title bar to move a window. RIGHT SHIFT closes.
 */
public class ClickGuiScreen extends Screen implements ModuleGui {

    /**
     * Resolution: the whole menu is laid out on a grid M times finer than a GUI pixel (text is drawn M times bigger,
     * every size below is M x the old one) and then scaled back down, so edges, text and shapes can sit on every
     * screen pixel instead of every GUI pixel. All the numbers below are in these fine units.
     */
    private static final int M = 4;           // higher fine-grid = sharper edges/text on retina/high-DPI
    private static final float FS = 10f * M;   // text size in fine units

    private static final int W = 118 * M, TITLE_H = 24 * M, ROW_H = 20 * M, SET_H = 18 * M, NUM_H = 28 * M,
            STRIP_X = 40 * M, STRIP_END = 18 * M, R = 3 * M, VISIBLE_ROWS = 11, GUT = 6 * M;   // GUT = room kept free for the scrollbar
    private static final int NAV_W = 96 * M, NAV_X = 8 * M, NAV_Y = 34 * M, NAV_HEAD = 26 * M, NAV_ROW = 20 * M;

    // ---- Vape-style palette: pure dark panels, white = enabled, no logo ----
    // Tuned to classic Vape click GUI (near-black frames, white active rows/text)
    private static final int C_BODY   = 0xF20C0C0C;   // window body
    private static final int C_HOVER  = 0xFF161616;   // row hover
    private static final int C_SET    = 0xFF101010;   // settings strip under modules
    private static final int C_TEXT   = 0xFFE8E8E8;   // primary text
    private static final int C_MUTED  = 0xFF6E6E6E;   // disabled / secondary
    private static final int C_TRACK  = 0xFF2A2A2A;   // slider track
    private static final int C_DARK   = 0xFF0A0A0A;   // deeper fill
    private static final int C_LINE   = 0xFF1C1C1C;   // separators
    private static final int C_BORDER = 0xFF252525;   // subtle outer edge

    /** Accent for enabled rows / sliders: pure white (Vape default) unless HUD tint is on. */
    private static int accent() {
        UiThemes.UiTheme th = UiThemes.current();
        if (!th.custom) return 0xFF000000 | th.accent;
        float s = dev.kuro.client.module.impl.Hud.saturation();
        if (s < 0.2f) return 0xFFFFFFFF;
        return 0xFF000000 | Mth.hsvToRgb(dev.kuro.client.module.impl.Hud.hue(), 0.55f * s, 1f);
    }

    /** Text colour that stays readable on the accent (black on white, white on colour). */
    private static int onAccent(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 140 ? 0xFF0A0A0A : 0xFFFFFFFF;
    }

    private static final int WHEEL_H = 104 * M, WS = 64 * M, BAR_DY = 90 * M, BAR_H = 8 * M;

    private static int settingH(Setting s) {
        if (s instanceof Setting.Hue h && h.wheel) return WHEEL_H;
        return s instanceof Setting.Num ? NUM_H : SET_H;
    }

    // ---- text in fine units ----
    private static int tw(String s) { return (int) Math.ceil(SdfText.width(SdfText.Face.SANS, s, FS)); }
    /** Top y for a line of text so its capitals are vertically centred in a box (top, height h). */
    private static int ty(int top, int h) { return top + h / 2 - Math.round(0.35f * FS); }
    private static void tx(GuiGraphics g, String s, int x, int y, int c) { SdfText.draw(g, SdfText.Face.SANS, s, x, y, FS, c); }
    private static String trim(String s, int max, boolean fromEnd) {
        if (tw(s) <= max) return s;
        String t = s;
        while (!t.isEmpty() && tw(t) > max) t = fromEnd ? t.substring(1) : t.substring(0, t.length() - 1);
        return t;
    }

    /** Where each colour-wheel row was last drawn (x, y, row width), so a click can be turned into a hue / saturation / brightness. */
    private final java.util.Map<Setting, int[]> wheelGeom = new java.util.HashMap<>();
    private int dragPart;   // 0 = slider / hue strip, 1 = colour wheel, 2 = brightness bar
    private int wheelCx, wheelCy, barX, barW;
    private static final List<Win> WINS = new ArrayList<>(); // static: positions survive reopening
    private static int placedKey;
    private static boolean placed;                          // windows are centred in a row the first time the menu opens

    private record Hit(int x, int y, int w, int h, int kind, Module module, Setting setting) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private static class Win {
        final Category cat; int x, y; boolean open = true; int scroll, maxScroll, gut; float pos, target;
        final List<Hit> hits = new ArrayList<>();
        Win(Category cat, int x, int y) { this.cat = cat; this.x = x; this.y = y; }
    }

    private Win dragWin;
    private int dragDx, dragDy;
    private Setting activeSlider;
    private int sliderX, sliderW;
    private Module listening;
    private Setting.Text typing;   // text box that has keyboard focus (Friends: type a name, Enter)
    private String query = "";
    private int lastMx, lastMy;
    private float ease = 1f;    // smooth scrolling speed for this frame
    private long lastNs;
    private String hoverFull;   // full text of a setting label that had to be shortened, shown while hovering it

    /** Shortens text with "..." so it always fits in max units. */
    private String fit(String s, int max) {
        if (tw(s) <= max) return s;
        String dots = "...";
        return trim(s, Math.max(0, max - tw(dots)), false) + dots;
    }

    /** Hue rows show the label without a trailing "color" and move the strip right for longer labels. */
    private static String hueLabel(Setting.Hue h) {
        String t = h.name.replaceAll("(?i)\\s+colou?r$", "");
        return t.isEmpty() ? h.name : t;
    }

    private int hueStart(Setting.Hue h) {
        return Math.max(STRIP_X, 8 * M + tw(hueLabel(h)) + 5 * M);
    }

    public ClickGuiScreen() {
        super(Component.literal(dev.kuro.client.module.impl.Hud.NAME));
        if (WINS.isEmpty()) {
            int i = 0;
            for (Category c : Category.values()) WINS.add(new Win(c, NAV_X + NAV_W + 10 * M + i++ * (W + 8 * M), NAV_Y));
        }
    }

    @Override
    protected void init() {
        fitScale();
        int key = width * 10000 + height;
        if (placedKey != key) placed = false;
        if (placed || WINS.isEmpty()) return;
        placedKey = key;
        int n = WINS.size(), gap = 8 * M, total = n * W + (n - 1) * gap;
        int left = NAV_X + NAV_W + 10 * M;
        int sx = left + Math.max(0, (vw() - left - 4 * M - total) / 2);
        int sy = Math.max(26 * M, NAV_Y);
        for (int i = 0; i < n; i++) { Win w = WINS.get(i); w.x = sx + i * (W + gap); w.y = sy; }
        placed = true;
    }

    /** True while waiting for a key to bind (so Right Shift can be bound instead of closing). */
    @Override public boolean isBinding() { return listening != null || typing != null; }

    @Override public boolean isPauseScreen() { return false; }

    // the game's own menu blur (HUD -> Menu blur); with it off the world stays sharp behind the windows
    @Override public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (dev.kuro.client.module.impl.Hud.menuBlur()) super.renderBackground(g, mx, my, delta);
    }

    // ---- scale: GUI pixels per fine unit. Always a whole number of SCREEN pixels per unit, so nothing is ever blurry ----
    private float uiScale = 1f / M;

    private void fitScale() {
        // Prefer crisp pixel snap. Higher M already subdivides layout; keep scale so text stays sharp.
        float fit = Math.min(1f, Math.min(width / 900f, height / 360f));
        float ideal = fit / M;
        double gs = minecraft != null && minecraft.getWindow() != null ? minecraft.getWindow().getGuiScale() : 1.0;
        // Snap to whole screen pixels per fine unit (never below 1px) for hard edges
        double px = Math.max(1.0, Math.round(ideal * gs));
        float eff = (float) (px / gs);
        if (eff * 900f * M > width + 2 || eff * 360f * M > height + 2) {
            // fall back to continuous fit on very small windows
            eff = ideal;
        }
        uiScale = eff;
    }

    /** Menu-space size of the screen in fine units. */
    private int vw() { return Math.round(width / uiScale); }
    private int vh() { return Math.round(height / uiScale); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        fitScale();
        var mat = g.pose();
        mat.pushMatrix();
        mat.scale(uiScale, uiScale);
        try { renderScaled(g, Math.round(mx / uiScale), Math.round(my / uiScale), delta); }
        finally { mat.popMatrix(); }
    }

    private void renderScaled(GuiGraphics g, int mx, int my, float delta) {
        dev.kuro.client.module.impl.Hud.applyTheme();
        dev.kuro.client.module.impl.Snowflakes.draw(g, vw(), vh());   // behind the windows; no-op unless the module is on
        lastMx = mx; lastMy = my; hoverFull = null;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        ease = 1f - (float) Math.exp(-dt * 18f);
        for (Win w : WINS) {   // keep every window on screen after a GUI scale / resolution change
            w.x = Mth.clamp(w.x, 0, Math.max(0, vw() - W));
            w.y = Mth.clamp(w.y, 0, Math.max(0, vh() - TITLE_H));
        }
        for (Win w : WINS) drawWindow(g, w, mx, my);
        drawNav(g, mx, my);
        drawSearch(g);
        drawPalette(g);
        if (hoverFull != null) {                                          // full label of a shortened setting
            int tipW = tw(hoverFull) + 4 * M * 2, tipH = 18 * M, tipR = 4 * M;
            int tipX = Math.max(4, Math.min(mx + 20, vw() - tipW - 4)), tipY = Math.max(4, Math.min(my + 24, vh() - tipH - 4));
            Ui.corners(g, tipX, tipY, tipX + tipW, tipY + tipH, tipR, 0xFF000000, true, true, true, true);
            Ui.ring(g, tipX, tipY, tipX + tipW, tipY + tipH, tipR, C_TRACK);
            tx(g, hoverFull, tipX + 4 * M, ty(tipY, tipH), C_TEXT);
        }
    }

    // palette button (bottom right) and search bar (bottom centre)
    private static final int PAL = 20 * M;
    private int palX() { return vw() - PAL - 8 * M; }
    private int palY() { return vh() - PAL - 8 * M; }
    private int searchY() { return vh() - 8 * M - SEARCH_H; }
    private static final int SEARCH_W = 130 * M, SEARCH_H = 20 * M;

    private static void dots(GuiGraphics g, int cx, int cy, int c) {
        int d = M * 3 / 2, gap = M * 4;      // 1.5 gui px dots, 4 gui px apart
        for (int i = -1; i <= 1; i++) g.fill(cx - d / 2, cy + i * gap - d / 2, cx - d / 2 + d, cy + i * gap - d / 2 + d, c);
    }

    /** Small "^" (up) or "v" (down) drawn from pixels. */
    private static void caret(GuiGraphics g, int cx, int cy, boolean up, int c) {
        int st = M;                                   // 1 gui px per step
        for (int r = 0; r < 4; r++) {
            int yy = up ? cy - 2 * st + st * r : cy + st - st * r;
            g.fill(cx - st * r - st, yy, cx - st * r, yy + st, c);
            g.fill(cx + st * r, yy, cx + st * r + st, yy + st, c);
        }
    }

    /** Left panel: minecraft name + one row per category. Click a row to open that window and bring it to the front. */
    private void drawNav(GuiGraphics g, int mx, int my) {
        Category[] cats = Category.values();
        int h = NAV_HEAD + cats.length * NAV_ROW + 6 * M;
        Ui.corners(g, NAV_X, NAV_Y, NAV_X + NAV_W, NAV_Y + h, R, C_BODY, true, true, true, true);
        SdfText.draw(g, SdfText.Face.BOLD, dev.kuro.client.module.impl.Hud.NAME, NAV_X + 10 * M, NAV_Y + NAV_HEAD / 2 - Math.round(0.35f * 9f * M), 9f * M, 0xFFFFFFFF);
        g.fill(NAV_X + 2 * M, NAV_Y + NAV_HEAD - M / 2, NAV_X + NAV_W - 2 * M, NAV_Y + NAV_HEAD, C_LINE);
        int acc = accent();
        for (int i = 0; i < cats.length; i++) {
            int ry = NAV_Y + NAV_HEAD + i * NAV_ROW;
            boolean hover = mx >= NAV_X && mx < NAV_X + NAV_W && my >= ry && my < ry + NAV_ROW;
            if (hover) Ui.corners(g, NAV_X + 3 * M, ry + M, NAV_X + NAV_W - 3 * M, ry + NAV_ROW - M, R, C_HOVER, true, true, true, true);
            int on = 0;
            for (Module m : KuroClient.modules.inCategory(cats[i])) if (m.isEnabled()) on++;
            tx(g, cats[i].label, NAV_X + 10 * M, ty(ry, NAV_ROW), C_TEXT);
            if (on > 0) { String c = String.valueOf(on); tx(g, c, NAV_X + NAV_W - 24 * M - tw(c), ty(ry, NAV_ROW), acc); }
            int ax = NAV_X + NAV_W - 14 * M, ay = ry + NAV_ROW / 2;   // ">" arrow, 1 gui px per step
            for (int k = 0; k < 4; k++) {
                g.fill(ax + M * k, ay - M * (k + 1), ax + M * k + M, ay - M * k, C_MUTED);
                g.fill(ax + M * k, ay + M * k, ax + M * k + M, ay + M * (k + 1), C_MUTED);
            }
        }
    }

    private int navRowAt(double mx, double my) {
        Category[] cats = Category.values();
        if (mx < NAV_X || mx >= NAV_X + NAV_W) return -1;
        int rel = (int) my - (NAV_Y + NAV_HEAD);
        if (rel < 0 || rel >= cats.length * NAV_ROW) return -1;
        return rel / NAV_ROW;
    }

    private void drawPalette(GuiGraphics g) {
        int px = palX(), py = palY();
        Ui.corners(g, px, py, px + PAL, py + PAL, R, C_BODY, true, true, true, true);
        int pad = 4 * M, gap = 2 * M, cell = (PAL - 2 * pad - gap) / 2, sr = M * 3 / 2;
        int[] sw = {0xFFFFFFFF, 0xFFB8B8B8, 0xFF7A7A7A, 0xFF404040};
        for (int i = 0; i < 4; i++) {
            int cx = px + pad + (i % 2) * (cell + gap), cy = py + pad + (i / 2) * (cell + gap);
            Ui.corners(g, cx, cy, cx + cell, cy + cell, sr, sw[i], true, true, true, true);
        }
    }

    /** Search bar at the bottom centre: just start typing. Filters every window to the matching modules. */
    private void drawSearch(GuiGraphics g) {
        int sw = SEARCH_W, sx = (vw() - sw) / 2, sy = searchY();
        Ui.corners(g, sx, sy, sx + sw, sy + SEARCH_H, R, C_BODY, true, true, true, true);
        Ui.ring(g, sx, sy, sx + sw, sy + SEARCH_H, R, query.isEmpty() ? C_BORDER : accent());
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (query.isEmpty()) {
            tx(g, "Search...", sx + 8 * M, ty(sy, SEARCH_H), C_MUTED);
        } else {
            String shown = trim(query, sw - 28 * M, true);
            tx(g, shown + (blink ? "_" : ""), sx + 8 * M, ty(sy, SEARCH_H), C_TEXT);
            tx(g, "x", sx + sw - 12 * M, ty(sy, SEARCH_H), C_MUTED);
        }
    }

    private boolean matches(Module m) {
        return query.isEmpty() || m.name.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT));
    }

    private void drawWindow(GuiGraphics g, Win w, int mx, int my) {
        w.hits.clear();
        w.pos += (w.target - w.pos) * ease;
        if (Math.abs(w.target - w.pos) < 0.5f) w.pos = w.target;
        w.scroll = Math.round(w.pos);
        int x = w.x, y = w.y;
        boolean any = false;
        for (Module m : KuroClient.modules.inCategory(w.cat)) if (matches(m)) { any = true; break; }
        if (!any) return;   // nothing in this window matches the search

        boolean showRows = w.open || !query.isEmpty();
        int viewTop = y + TITLE_H;
        int viewBot = Math.min(Math.max(viewTop + ROW_H, vh() - 44 * M), viewTop + VISIBLE_ROWS * ROW_H + 4 * M);
        int contentH = R;   // bottom margin: rows stop short of the rounded corners
        for (Module m : KuroClient.modules.inCategory(w.cat)) {
            if (!matches(m)) continue;
            contentH += ROW_H;
            if (m.expanded) for (Setting st : m.settings) contentH += settingH(st);
        }
        int bodyH = showRows ? Math.min(contentH, viewBot - viewTop) : 0;
        int bottom = viewTop + bodyH;
        int acc = accent();
        boolean scrolls = contentH > bodyH;
        w.gut = scrolls ? GUT : 0;         // enabled rows / settings end before the scrollbar instead of running under it
        int rowW = W - w.gut;

        // flat near-black window (Vape-style)
        Ui.corners(g, x, y, x + W, bottom, R, C_BODY, true, true, true, true);
        // thin outer edge so frames read clearly on dark game backgrounds
        Ui.ring(g, x, y, x + W, bottom, R, C_BORDER);

        // header: hamburger lines + category name, caret on the right (no logo)
        int lw = 6 * M, lt = M, lg = 2 * M;                          // hamburger: 3 lines, 6 x 1 gui px, 2 gui px apart
        int iy = y + TITLE_H / 2 - lg - lt / 2;
        for (int i = 0; i < 3; i++) g.fill(x + 6 * M, iy + i * lg, x + 6 * M + lw, iy + i * lg + lt, 0xFFFFFFFF);
        tx(g, w.cat.label, x + 14 * M, ty(y, TITLE_H), 0xFFFFFFFF);
        caret(g, x + W - 9 * M, y + TITLE_H / 2, showRows, C_MUTED);
        // separator under title bar
        g.fill(x + 3 * M, y + TITLE_H - M / 2, x + W - 3 * M, y + TITLE_H, C_LINE);
        w.hits.add(new Hit(x, y, W, TITLE_H, 0, null, null));

        if (!showRows) { w.maxScroll = 0; return; }

        int cy = viewTop - w.scroll;
        g.enableScissor(x, viewTop, x + W, viewTop + bodyH - R);   // rows never reach the bottom corners
        for (Module m : KuroClient.modules.inCategory(w.cat)) {
            if (!matches(m)) continue;
            boolean hover = mx >= x && mx < x + rowW && my >= Math.max(cy, viewTop) && my < Math.min(cy + ROW_H, viewTop + bodyH - R) && my >= cy;
            boolean on = m.isEnabled();
            // enabled = soft inset pill in the accent colour with a bright bar on the left and white text;
            // disabled = plain row (subtle inset pill on hover)
            int pad = 3 * M, pr = R;
            if (on) {
                Ui.corners(g, x + pad, cy + M, x + rowW - pad, cy + ROW_H - M, pr, (acc & 0xFFFFFF) | 0x40000000, true, true, true, true);
                Ui.corners(g, x + pad + M, cy + ROW_H / 2 - 5 * M, x + pad + 2 * M + M / 2, cy + ROW_H / 2 + 5 * M, M / 2, acc, true, true, true, true);
            } else if (hover) {
                Ui.corners(g, x + pad, cy + M, x + rowW - pad, cy + ROW_H - M, pr, C_HOVER, true, true, true, true);
            }
            int fg = on ? 0xFFFFFFFF : C_TEXT;
            int sub = on ? 0xB0FFFFFF : C_MUTED;
            int right = x + rowW - 8 * M;
            if (!m.settings.isEmpty()) { dots(g, x + rowW - 8 * M, cy + ROW_H / 2, sub); right = x + rowW - 14 * M; }
            String tag = listening == m ? "..." : m.key > 0 ? "[" + keyName(m.key) + "]" : "";
            int tagW = tag.isEmpty() ? 0 : tw(tag) + 6;
            if (!tag.isEmpty()) tx(g, tag, right - tagW + 6, ty(cy, ROW_H), sub);
            String shownName = fit(m.name, right - tagW - (x + 8 * M));
            tx(g, shownName, x + 8 * M, ty(cy, ROW_H), fg);
            if (hover && !shownName.equals(m.name)) hoverFull = m.name;

            if (cy + ROW_H > viewTop && cy < viewTop + bodyH) w.hits.add(new Hit(x, Math.max(cy, viewTop), rowW, ROW_H - Math.max(0, viewTop - cy), 1, m, null));
            cy += ROW_H;
            if (m.expanded) {
                for (Setting st : m.settings) {
                    int sh = settingH(st);
                    if (cy + sh > viewTop && cy < viewTop + bodyH) drawSetting(g, w, m, st, x, cy, sh, rowW, viewTop);
                    cy += sh;
                }
            }
        }
        g.disableScissor();
        w.maxScroll = Math.max(0, contentH - bodyH);
        w.target = Mth.clamp(w.target, 0, w.maxScroll);
        w.pos = Mth.clamp(w.pos, 0, w.maxScroll);
        w.scroll = Math.round(w.pos);
        if (w.maxScroll > 0) {   // scrollbar lives in its own gutter on the right
            int trackH = bodyH - R;
            int th = Math.max(20, trackH * trackH / contentH);
            int ty = viewTop + (int) ((trackH - th) * (w.scroll / (double) w.maxScroll));
            Ui.corners(g, x + W - 9, ty, x + W - 5, ty + th, 2, 0xFF4A4A4A, true, true, true, true);
        }
    }

    private void drawSetting(GuiGraphics g, Win w, Module m, Setting s, int x, int y, int h, int rowW, int viewTop) {
        int acc = accent();
        g.fill(x, y, x + rowW, y + h, C_SET);
        boolean over = lastMy >= viewTop && lastMx >= x && lastMx < x + rowW && lastMy >= y && lastMy < y + h;
        int ty = ty(y, SET_H);
        if (s instanceof Setting.Bool b) {
            String shown = fit(b.name, rowW - 8 * M - 14 * M - 8 * M - 4 * M);                 // label area ends before the switch
            tx(g, shown, x + 8 * M, ty, C_TEXT);
            if (over && !shown.equals(b.name)) hoverFull = b.name;
            int swW = 14 * M, swH = 7 * M, bx = x + rowW - 8 * M - swW, by = y + (h - swH) / 2;   // pill switch
            Ui.corners(g, bx, by, bx + swW, by + swH, swH / 2, b.value ? acc : C_TRACK, true, true, true, true);
            int kd = swH - M, kx = b.value ? bx + swW - kd - M / 2 : bx + M / 2;
            Ui.corners(g, kx, by + M / 2, kx + kd, by + M / 2 + kd, kd / 2, b.value ? onAccent(acc) : 0xFF8A8A8A, true, true, true, true);
        } else if (s instanceof Setting.Num n) {
            // label left, value right, thin slider with a round knob underneath
            String v = n.display();
            int valW = tw(v);
            String shown = fit(n.name, rowW - 16 * M - 4 * M - valW);
            int lblY = ty(y, SET_H);
            tx(g, shown, x + 8 * M, lblY, C_TEXT);
            tx(g, v, x + rowW - 8 * M - valW, lblY, C_MUTED);
            if (over && !shown.equals(n.name)) hoverFull = n.name + ": " + v;
            int sx = x + 8 * M, sw = rowW - 16 * M, ly = y + h - 7 * M;
            int th = M, kr = 2 * M + M / 2;                       // track 1 gui px thick, knob ~2.5 gui px radius
            Ui.corners(g, sx, ly - th / 2 - 1, sx + sw, ly + th / 2 + 1, th / 2 + 1, C_TRACK, true, true, true, true);
            int kx = sx + (int) Math.round(n.fraction() * sw);
            if (kx > sx) Ui.corners(g, sx, ly - th / 2 - 1, kx, ly + th / 2 + 1, th / 2 + 1, acc, true, true, true, true);
            Ui.corners(g, kx - kr, ly - kr, kx + kr, ly + kr, kr, acc, true, true, true, true);
        } else if (s instanceof Setting.Hue hu && hu.wheel) {
            tx(g, hueLabel(hu), x + 8 * M, ty, C_TEXT);
            Ui.corners(g, x + rowW - 8 * M - 8 * M, y + SET_H / 2 - 2 * M, x + rowW - 8 * M, y + SET_H / 2 + 2 * M, M, hu.argb(255), true, true, true, true);
            int top = y + SET_H + 4, cx = x + rowW / 2, cy = top + WS / 2;
            wheelGeom.put(s, new int[]{x, y, rowW});
            Ui.wheel(g, cx - WS / 2, top, cx + WS / 2, top + WS, hu.val);
            // picker knob: ring around the chosen colour
            double ang = (hu.hue - 0.5) * Math.PI * 2.0;
            int kx = cx + (int) Math.round(Math.cos(ang) * hu.sat * (WS / 2 - 2));
            int ky = cy + (int) Math.round(Math.sin(ang) * hu.sat * (WS / 2 - 2));
            Ui.corners(g, kx - 8, ky - 8, kx + 8, ky + 8, 8, 0xFFFFFFFF, true, true, true, true);
            Ui.corners(g, kx - 6, ky - 6, kx + 6, ky + 6, 6, hu.argb(255), true, true, true, true);
            // brightness bar: black -> the chosen colour at full brightness
            int bx = x + 20, bw = rowW - 40, by = y + BAR_DY;
            int full = 0xFF000000 | (Mth.hsvToRgb(hu.hue, hu.sat, 1f) & 0xFFFFFF);
            Ui.corners(g, bx, by, bx + bw, by + BAR_H, 8, 0xFF000000, true, true, true, true);
            Ui.gradient(g, bx, by, bx + bw, by + BAR_H, 8, 0xFF000000, full);
            int bk = bx + Math.round(hu.val * bw);
            Ui.corners(g, bk - 6, by - 2, bk + 6, by + BAR_H + 2, 6, 0xFFFFFFFF, true, true, true, true);
        } else if (s instanceof Setting.Hue hu) {
            tx(g, hueLabel(hu), x + 8 * M, ty, C_TEXT);
            int off = hueStart(hu);
            int sx = x + off, sw = rowW - off - STRIP_END;
            Ui.hueStrip(g, sx, y + 10, sx + sw, y + h - 12, 4);   // rainbow drawn by a shader
            int mxp = sx + (int) (hu.hue * sw);
            g.fill(mxp - 2, y + 6, mxp + 2, y + h - 8, 0xFFFFFFFF);
            Ui.corners(g, x + rowW - 14 * M, y + h / 2 - 3 * M, x + rowW - 8 * M, y + h / 2 + 3 * M, M, hu.argb(255), true, true, true, true);
        } else if (s instanceof Setting.Text t) {
            boolean focus = typing == t;
            Ui.corners(g, x + 6 * M, y + M, x + rowW - 6 * M, y + h - M, 2 * M, C_DARK, true, true, true, true);
            if (focus) Ui.ring(g, x + 6 * M, y + M, x + rowW - 6 * M, y + h - M, 2 * M, acc);
            String caret = focus && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
            if (t.value.isEmpty()) {
                tx(g, fit(t.name, rowW - 16 * M - 4 * M), x + 8 * M, ty, C_MUTED);
                if (!caret.isEmpty()) tx(g, caret, x + 8 * M, ty, C_TEXT);
            } else {
                tx(g, trim(t.value, rowW - 16 * M - 4 * M, true) + caret, x + 8 * M, ty, C_TEXT);
            }
        } else if (s instanceof Setting.Button btn) {
            Ui.corners(g, x + 6 * M, y + M, x + rowW - 6 * M, y + h - M, 2 * M, over ? 0xFF262626 : 0xFF1C1C1C, true, true, true, true);
            String shown = fit(btn.name, rowW - 16 * M - 4 * M);
            tx(g, shown, x + (rowW - tw(shown)) / 2, ty, C_TEXT);
            if (over && !shown.equals(btn.name)) hoverFull = btn.name;
        }
        int hy = Math.max(y, viewTop);
        w.hits.add(new Hit(x, hy, rowW, h - (hy - y), 2, m, s));
    }

    // ---------------- input ----------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x() / uiScale, my = click.y() / uiScale;
        int button = click.button();
        typing = null;   // clicking anywhere drops text focus; clicking a text box again (see handle) re-focuses it
        int sw = SEARCH_W, sx = (vw() - sw) / 2, sy = searchY();
        int nav = navRowAt(mx, my);
        if (nav >= 0) {   // category in the left panel: unfold its window and bring it to the front
            Category c = Category.values()[nav];
            for (int i = 0; i < WINS.size(); i++) if (WINS.get(i).cat == c) {
                Win w = WINS.remove(i); w.open = true; WINS.add(w); break;
            }
            return true;
        }
        if (mx >= palX() && mx < palX() + PAL && my >= palY() && my < palY() + PAL) {
            if (button == 1) dev.kuro.client.module.impl.Hud.resetAccent(); else dev.kuro.client.module.impl.Hud.cycleAccent();
            return true;
        }
        if (!query.isEmpty() && mx >= sx + sw - 18 * M && mx < sx + sw && my >= sy && my < sy + SEARCH_H) { query = ""; return true; }
        for (int i = WINS.size() - 1; i >= 0; i--) {
            Win w = WINS.get(i);
            for (Hit h : w.hits) {
                if (h.contains(mx, my)) {
                    WINS.remove(i);
                    WINS.add(w);          // bring to front
                    handle(w, h, button, mx, my);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void handle(Win w, Hit h, int button, double mx, double my) {
        switch (h.kind()) {
            case 0 -> {
                if (button == 0 && (mx < h.x() + 16 * M || mx >= h.x() + W - 16 * M)) w.open = !w.open;   // icon / caret folds the window
                else if (button == 0) { dragWin = w; dragDx = (int) mx - w.x; dragDy = (int) my - w.y; }
                else if (button == 1) w.open = !w.open;
            }
            case 1 -> {
                Module m = h.module();
                if (button == 0 && !m.settings.isEmpty() && mx >= h.x() + h.w() - 16 * M) m.expanded = !m.expanded;   // three dots open the settings
                else if (button == 0) m.toggle();
                else if (button == 1 && !m.settings.isEmpty()) m.expanded = !m.expanded;
                else if (button == 2) listening = m;
            }
            case 2 -> {
                if (button != 0) return;
                Setting s = h.setting();
                if (s instanceof Setting.Bool b) b.value = !b.value;
                else if (s instanceof Setting.Button btn) btn.action.run();
                else if (s instanceof Setting.Text t) typing = t;
                else if (s instanceof Setting.Num) { dragPart = 0; startSlider(s, h.x() + 8 * M, h.w() - 16 * M, mx); }
                else if (s instanceof Setting.Hue hue && hue.wheel) startWheel(hue, mx, my);
                else if (s instanceof Setting.Hue hue) { int off = hueStart(hue); dragPart = 0; startSlider(s, h.x() + off, h.w() - off - STRIP_END, mx); }
            }
            default -> {}
        }
    }

    private void startSlider(Setting s, int x, int w, double mx) {
        activeSlider = s; sliderX = x; sliderW = w;
        applySlider(mx, lastMy);
    }

    private void startWheel(Setting.Hue hu, double mx, double my) {
        int[] gm = wheelGeom.get(hu);
        if (gm == null || my < gm[1] + SET_H) return;          // the label row does nothing
        activeSlider = hu;
        wheelCx = gm[0] + gm[2] / 2;
        wheelCy = gm[1] + SET_H + 4 + WS / 2;
        barX = gm[0] + 20; barW = gm[2] - 40;
        dragPart = my >= gm[1] + BAR_DY - 10 ? 2 : 1;           // bottom strip = brightness, above it = wheel
        applySlider(mx, my);
    }

    private void applySlider(double mx, double my) {
        if (activeSlider instanceof Setting.Num n) n.setFraction((mx - sliderX) / sliderW);
        else if (activeSlider instanceof Setting.Hue h) {
            if (!h.wheel || dragPart == 0) h.hue = (float) Mth.clamp((mx - sliderX) / sliderW, 0.0, 0.999);
            else if (dragPart == 2) h.val = (float) Mth.clamp((mx - barX) / barW, 0.03, 1.0);
            else {
                double dx = mx - wheelCx, dy = my - wheelCy, r = WS / 2.0 - 2;
                h.hue = (float) Mth.clamp(Math.atan2(dy, dx) / (Math.PI * 2.0) + 0.5, 0.0, 0.999);
                h.sat = (float) Mth.clamp(Math.hypot(dx, dy) / r, 0.0, 1.0);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        mx /= uiScale; my /= uiScale;
        for (int i = WINS.size() - 1; i >= 0; i--) {
            Win w = WINS.get(i);
            if (!(w.open || !query.isEmpty()) || w.maxScroll <= 0) continue;
            if (mx >= w.x && mx < w.x + W && my >= w.y && my < vh()) {
                w.target = Mth.clamp(w.target - (float) vAmount * 60f, 0, w.maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (dragWin != null) {
            dragWin.x = Mth.clamp((int) (click.x() / uiScale) - dragDx, 0, Math.max(0, vw() - W));
            dragWin.y = Mth.clamp((int) (click.y() / uiScale) - dragDy, 0, Math.max(0, vh() - TITLE_H));
            return true;
        }
        if (activeSlider != null) { applySlider(click.x() / uiScale, click.y() / uiScale); return true; }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        dragWin = null;
        activeSlider = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (typing != null) {
            int cp = input.codepoint();
            if (typing.value.length() < 32 && !Character.isISOControl(cp) && !Character.isWhitespace(cp))
                typing.value += new String(Character.toChars(cp));
            return true;
        }
        if (listening != null) return false;
        String t = new String(Character.toChars(input.codepoint()));
        if (t.isBlank() && query.isEmpty()) return true;
        if (query.length() < 32 && !Character.isISOControl(input.codepoint())) {
            query += t;
            for (Win w : WINS) { w.scroll = 0; w.pos = 0; w.target = 0; }
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.input();
        if (typing != null) {   // a text box has focus: it gets every key (Right Shift included)
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) typing.submit();
            else if (key == GLFW.GLFW_KEY_ESCAPE) typing = null;
            else if (key == GLFW.GLFW_KEY_BACKSPACE) { if (!typing.value.isEmpty()) typing.value = typing.value.substring(0, typing.value.length() - 1); }
            else if (key == GLFW.GLFW_KEY_V && minecraft != null && minecraft.getWindow() != null
                    && (GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)) {
                String clip = GLFW.glfwGetClipboardString(minecraft.getWindow().handle());   // Ctrl+V
                if (clip != null) for (char ch : clip.toCharArray())
                    if (!Character.isISOControl(ch) && !Character.isWhitespace(ch) && typing.value.length() < 32) typing.value += ch;
            }
            return true;
        }
        if (listening == null && !query.isEmpty()) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) { query = query.substring(0, query.length() - 1); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE) { query = ""; return true; }
        }
        if (listening != null) {
            boolean clear = key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE;
            listening.key = clear ? -1 : key;
            listening = null;
            return true;
        }
        if (key == dev.kuro.client.util.MenuKey.key()) { KuroClient.modules.consumeGuiKey(); onClose(); return true; }
        return super.keyPressed(input);
    }

    private static String keyName(int key) {
        String n = GLFW.glfwGetKeyName(key, 0);
        return n != null ? n.toUpperCase(java.util.Locale.ROOT) : "K" + key;
    }
}
