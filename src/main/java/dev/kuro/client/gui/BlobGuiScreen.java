package dev.kuro.client.gui;

import dev.kuro.client.KuroClient;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.module.impl.BlobUi;
import dev.kuro.client.module.impl.Hud;
import dev.kuro.client.module.impl.Snowflakes;
import dev.kuro.client.module.impl.UsernameHider;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.ShaderUi;
import dev.kuro.client.util.Ui;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Blob UI" menu (module/impl/BlobUi.java), laid out like the radial menu: your player's head sits in a soft blob in the
 * middle and one blobby ring tab per category is spread around it. Click a tab to open its category as a glass panel (left
 * click a module = toggle, chevron / right click = settings, middle click = bind a key); the round buttons on top switch
 * category, Esc or the back arrow returns to the ring.
 * All shapes are shader quads (ShaderUi.segment / blob / Ui.panel / Ui.selected / Ui.hueStrip) and all text is shader text
 * (SdfText), so the whole menu is a few dozen draws and stays sharp at any scale.
 */
public class BlobGuiScreen extends Screen implements ModuleGui {
    private static final int DW = 520, DH = 400;                 // design size; scaled down to fit smaller screens
    private static final int PW = 220, ROW_H = 16, SET_H = 15;
    private static final Category[] CATS = Category.values();
    private static final Identifier[] ICONS = new Identifier[11];
    private static final Identifier CROWN = Identifier.fromNamespaceAndPath("kuro", "textures/gui/icons/crown.png");
    static { for (int i = 0; i < ICONS.length; i++) ICONS[i] = Identifier.fromNamespaceAndPath("kuro", "textures/gui/icons/nav_" + i + ".png"); }

    private static final int TEXT = 0xFFF2F5FA, MUTED = 0xFFA7B0C2, DARK = 0xFF14161B;

    private record Hit(int x, int y, int w, int h, int kind, Module module, Setting setting, Category cat) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<Hit> hits = new ArrayList<>();
    private Category page;                    // null = hub
    private float pageT, openT;
    private final float[] hov = new float[CATS.length];
    private float scroll, scrollTarget, maxScroll;
    private Module listening;
    private Setting.Text typing;
    private Setting activeSlider;
    private int sliderX, sliderW;
    private String query = "";
    private float uiScale = 1f;
    private long lastNs;
    private float dt = 0.016f;
    private int lastMx, lastMy;

    public BlobGuiScreen() { super(Component.literal(Hud.NAME)); }

    // ---- UI sounds -------------------------------------------------------------------------------------------------
    private int hoverKey = Integer.MIN_VALUE;
    private long lastScrollSnd;
    private void hoverSound(double mx, double my) {
        int key = 0;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains(mx, my) && h.w() > 0 && h.h() > 0) { key = java.util.Objects.hash(h.kind(), (int) h.x(), (int) h.y()); break; }
        }
        if (key != hoverKey) {
            hoverKey = key;
        }
    }
    private void scrollSound() {
        // scrolling is silent (the scroll tick used to repeat on every wheel notch)
    }

    @Override public void removed() { super.removed(); }

    @Override public boolean isBinding() { return listening != null || typing != null; }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (Hud.menuBlur()) super.renderBackground(g, mx, my, delta);
    }

    // ---------------- helpers ----------------

    private int vw() { return Math.round(width / uiScale); }
    private int vh() { return Math.round(height / uiScale); }

    private static int alpha(int argb, float f) { return (Mth.clamp(Math.round((argb >>> 24) * f), 0, 255) << 24) | (argb & 0xFFFFFF); }
    private static int a(int argb, int al) { return (Mth.clamp(al, 0, 255) << 24) | (argb & 0xFFFFFF); }

    private int acc() {
        UiThemes.UiTheme t = UiThemes.current();
        return t.custom ? 0xFF000000 | Mth.hsvToRgb(Hud.hue(), Math.max(0.30f, Hud.saturation()), 1f) : 0xFF000000 | t.accent;
    }
    private int tint(float v, int al) { return (al << 24) | (Mth.hsvToRgb(Hud.hue(), 0.45f * Hud.saturation(), v) & 0xFFFFFF); }

    private static final float BASE = SdfText.BASE;

    private int tw(String s) { return (int) Math.ceil(SdfText.width(SdfText.Face.SANS, s, BASE)); }
    private void txt(GuiGraphics g, String s, float x, float y, int color) { txt(g, s, x, y, 1f, color, false); }

    private void txt(GuiGraphics g, String s, float x, float y, float sc, int color, boolean bold) {
        SdfText.draw(g, bold ? SdfText.Face.BOLD : SdfText.Face.SANS, s, x, y, BASE * sc, color);
    }

    private void centered(GuiGraphics g, String s, float cx, float y, float sc, int color, boolean bold) {
        SdfText.centered(g, bold ? SdfText.Face.BOLD : SdfText.Face.SANS, s, cx, y, BASE * sc, color);
    }

    private String fit(String s, int max) {
        if (tw(s) <= max) return s;
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n).stripTrailing() + "...";
            if (tw(t) <= max) return t;
        }
        return "...";
    }

    private static void icon(GuiGraphics g, int idx, int x, int y, int size, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, ICONS[idx], x, y, 0f, 0f, size, size, 32, 32, 32, 32, color);
    }

    private static int iconOf(Category c) {
        return switch (c) { case COMBAT -> 0; case RENDER -> 1; case MOVEMENT -> 2; case PLAYER -> 3; case BASE -> 4; default -> 5; };
    }

    private boolean matches(Module m) { return query.isEmpty() || m.name.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)); }

    private static String keyName(int key) {
        String n = GLFW.glfwGetKeyName(key, 0);
        return n != null ? n.toUpperCase(java.util.Locale.ROOT) : "K" + key;
    }

    // ---------------- render ----------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        hoverSound(mx / uiScale, my / uiScale);
        uiScale = Mth.clamp(Math.min(width / (float) DW, height / (float) DH), 0.4f, 1f);
        var mat = g.pose();
        mat.pushMatrix();
        mat.scale(uiScale, uiScale);
        try { renderScaled(g, Math.round(mx / uiScale), Math.round(my / uiScale)); }
        finally { mat.popMatrix(); }
    }

    private void renderScaled(GuiGraphics g, int mx, int my) {
        Hud.applyTheme();
        lastMx = mx; lastMy = my;
        long now = System.nanoTime();
        dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        float ease = 1f - (float) Math.exp(-dt * 14f);
        openT = Math.min(1f, openT + dt * 3.5f);
        pageT += ((page != null ? 1f : 0f) - pageT) * (1f - (float) Math.exp(-dt * 12f));
        if (Math.abs((page != null ? 1f : 0f) - pageT) < 0.004f) pageT = page != null ? 1f : 0f;
        hits.clear();

        if (BlobUi.snow()) Snowflakes.draw(g, vw(), vh());
        float hubA = (1f - pageT) * openT;
        if (hubA > 0.02f) drawHub(g, mx, my, hubA, ease);
        if (page != null && pageT > 0.02f) drawPage(g, mx, my, pageT, ease);
        String hint = page == null ? "Click a blob   Right Shift = close" : "Esc = back   middle click = bind   type to search";
        centered(g, hint, vw() / 2f, vh() - 14, 0.8f, a(MUTED, Math.round(160 * openT)), false);
    }

    // ---- hub: your head in the middle, one blobby tab per category around it (like the radial menu) ----

    private static final float R_OUT = 144f;                          // outer radius of the tab ring in design px
    private int hubCx, hubCy;

    /** Which tab is under (mx, my): -1 over the middle or outside the ring. Tab 0 is at the top, then clockwise. */
    private int segmentAt(double mx, double my) {
        double dx = mx - hubCx, dy = my - hubCy, d = Math.hypot(dx, dy);
        if (d < R_OUT * 0.56 - 6 || d > R_OUT + 12) return -1;
        int n = CATS.length;
        double step = Math.PI * 2 / n;
        int idx = (int) Math.round((Math.atan2(dy, dx) + Math.PI / 2) / step);
        return ((idx % n) + n) % n;
    }

    private void drawHub(GuiGraphics g, int mx, int my, float fade, float ease) {
        hubCx = vw() / 2; hubCy = vh() / 2 + 6;
        int cx = hubCx, cy = hubCy, n = CATS.length;
        boolean interact = page == null;
        float grow = 0.72f + 0.28f * openT * openT * (3f - 2f * openT);
        float rOut = R_OUT * grow, rMid = R_OUT * 0.80f * grow;
        int over = interact ? segmentAt(mx, my) : -1;

        float[] ox = new float[n], oy = new float[n], dirX = new float[n], dirY = new float[n];
        for (int i = 0; i < n; i++) {
            hov[i] += ((i == over ? 1f : 0f) - hov[i]) * ease;
            double ang = Math.toRadians(-90) + i * (Math.PI * 2 / n);
            dirX[i] = (float) Math.cos(ang); dirY[i] = (float) Math.sin(ang);
            ox[i] = cx + dirX[i] * 8f * hov[i]; oy[i] = cy + dirY[i] * 8f * hov[i];   // hovered tab pops outwards
        }
        for (int i = 0; i < n; i++) {
            float h = hov[i];
            ShaderUi.segment(g, false, i, n, ox[i], oy[i], rOut, alpha(tint(0.40f + 0.12f * h, 0x66 + Math.round(0x28 * h)), fade));
        }
        for (int i = 0; i < n; i++) {
            float h = hov[i];
            ShaderUi.segment(g, true, i, n, ox[i], oy[i], rOut, alpha(a(0xFFFFFF, 0xD0 + Math.round(0x2F * h)), fade));
            Category c = CATS[i];
            boolean side = Math.abs(dirY[i]) < 0.9f;
            float rc = side ? rMid * 0.925f : rMid;                  // side tabs: content sits further in, where the shape is widest across
            float tx = ox[i] + dirX[i] * rc, ty = oy[i] + dirY[i] * rc;
            int white = a(0xFFFFFF, Math.round(255 * fade));
            // the side tabs are slanted, so there is less room across: shrink or wrap long names to stay inside the shape
            float maxW = side ? 50f : 96f;
            float lw = SdfText.width(SdfText.Face.BOLD, c.label, BASE * 1.05f);
            int sp = c.label.indexOf(' ');
            boolean two = lw > maxW && sp > 0;
            float lsc = 1.05f;
            if (!two && lw > maxW) lsc = 1.05f * maxW / lw;
            int top = two ? 30 : 26;
            icon(g, iconOf(c), Math.round(tx) - 13, Math.round(ty) - top, 26, white);
            if (two) {
                centered(g, c.label.substring(0, sp), tx, ty - 3, 1.0f, white, true);
                centered(g, c.label.substring(sp + 1), tx, ty + 8, 1.0f, white, true);
            } else {
                centered(g, c.label, tx, ty + 2, lsc, white, true);
            }
            if (BlobUi.counts()) {
                int on = 0, all = 0;
                for (Module m : KuroClient.modules.inCategory(c)) { all++; if (m.isEnabled()) on++; }
                centered(g, on + " / " + all + " on", tx, ty + (two ? 21 : 16), 0.75f, a(on > 0 ? acc() : MUTED, Math.round(220 * fade)), false);
            }
        }

        // middle: a soft blob with YOUR skin's head, the username under it and the version below that
        int cr = Math.round(74 * grow);
        ShaderUi.blob(g, cx, cy, cr, cr, 21, alpha(tint(0.42f, 0x70), fade), alpha(a(0xFFFFFF, 0xE8), fade));
        boolean drewHead = false;
        if (fade > 0.55f && minecraft != null && minecraft.player != null) {
            try {
                PlayerFaceRenderer.draw(g, minecraft.player.getSkin(), cx - 26, cy - 40, 52);   // face + hat layer of the current skin
                drewHead = true;
            } catch (RuntimeException ignored) { }
        }
        if (!drewHead) g.blit(RenderPipelines.GUI_TEXTURED, CROWN, cx - 16, cy - 34, 0f, 0f, 32, 32, 32, 32, 32, 32, a(acc(), Math.round(255 * fade)));
        String name = UsernameHider.apply(minecraft != null ? minecraft.getUser().getName() : "player");
        centered(g, fit(name, 84), cx, cy + 20, 1.0f, a(0xFFFFFF, Math.round(255 * fade)), true);
        centered(g, "v" + Hud.VERSION, cx, cy + 34, 0.7f, a(MUTED, Math.round(230 * fade)), false);
    }

    // ---- category page: round category buttons + a glass panel with the module list ----

    private void drawPage(GuiGraphics g, int mx, int my, float fade, float ease) {
        int vw = vw(), vh = vh();
        // category switcher
        int n = CATS.length, bs = 26, gap = 8, total = n * bs + (n - 1) * gap, nx = (vw - total) / 2, ny = 46;
        for (int i = 0; i < n; i++) {
            int x = nx + i * (bs + gap);
            boolean cur = CATS[i] == page, hover = mx >= x && mx < x + bs && my >= ny && my < ny + bs;
            hov[i] += ((hover || cur ? 1f : 0f) - hov[i]) * ease;
            float h = hov[i];
            Ui.panel(g, x, ny, x + bs, ny + bs, bs / 2, alpha(tint(0.30f + 0.12f * h, 0x60 + Math.round(0x30 * h)), fade),
                    alpha(a(0xFFFFFF, 0x80 + Math.round(0x7F * h)), fade), cur ? alpha(0x90FFFFFF, fade) : 0, false);
            icon(g, iconOf(CATS[i]), x + 6, ny + 6, 14, a(TEXT, Math.round(255 * fade)));
            hits.add(new Hit(x, ny, bs, bs, 1, null, null, CATS[i]));
        }

        int px = (vw - PW) / 2, py = ny + bs + 12, bottom = Math.max(py + 90, vh - 30);
        List<Module> mods = new ArrayList<>();
        for (Module m : KuroClient.modules.inCategory(page)) if (matches(m)) mods.add(m);
        int rows = 0;
        for (Module m : mods) rows += 1 + (m.expanded ? m.settings.size() : 0);
        int contentH = rows * ROW_H + 6;
        int viewTop = py + 26, maxView = bottom - 6 - viewTop;
        int viewH = Math.min(contentH, maxView);
        int panelBottom = viewTop + viewH + 6;

        Ui.panel(g, px, py, px + PW, panelBottom, 10, alpha(tint(0.22f, 0x66), fade), alpha(a(0xFFFFFF, 0xD0), fade), alpha(0x90FFFFFF, fade * 0.8f), true);
        // header: back arrow, category name, search text
        boolean backHover = mx >= px + 4 && mx < px + 26 && my >= py + 3 && my < py + 21;
        Ui.corners(g, px + 6, py + 5, px + 24, py + 19, 7, alpha(a(0xFFFFFF, backHover ? 0x38 : 0x18), fade), true, true, true, true);
        centered(g, "<", px + 15, py + 8, 1.0f, a(TEXT, Math.round(255 * fade)), true);
        hits.add(new Hit(px + 4, py + 3, 22, 18, 0, null, null, null));
        String head = query.isEmpty() ? page.label : page.label + "  /  " + query;
        centered(g, head, px + PW / 2f, py + 8, 1.0f, a(TEXT, Math.round(255 * fade)), true);
        Ui.corners(g, px + 12, viewTop - 3, px + PW - 12, viewTop - 2, 0, alpha(0x30FFFFFF, fade), true, true, true, true);

        scroll += (scrollTarget - scroll) * ease;
        maxScroll = Math.max(0, contentH - viewH);
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll);
        scroll = Mth.clamp(scroll, 0, maxScroll);

        int cy = viewTop + 2 - Math.round(scroll);
        g.enableScissor(px, viewTop, px + PW, viewTop + viewH);
        for (Module m : mods) {
            boolean on = m.isEnabled();
            boolean hover = mx >= px && mx < px + PW && my >= Math.max(cy, viewTop) && my < Math.min(cy + ROW_H, viewTop + viewH) && my >= cy;
            if (on) Ui.selected(g, px + 6, cy, px + PW - 6, cy + ROW_H - 1, 6, alpha(a(acc(), 0xFF), fade), m.name);
            else if (hover) Ui.corners(g, px + 6, cy, px + PW - 6, cy + ROW_H - 1, 6, alpha(0x26FFFFFF, fade), true, true, true, true);
            int fg = a(TEXT, Math.round(255 * fade));
            int right = px + PW - 12;
            if (!m.settings.isEmpty()) {
                centered(g, m.expanded ? "v" : ">", px + PW - 15, cy + 4, 0.9f, on ? fg : a(MUTED, Math.round(255 * fade)), false);
                right = px + PW - 24;
            }
            String tag = listening == m ? "..." : m.key > 0 ? "[" + keyName(m.key) + "]" : "";
            int tagW = tag.isEmpty() ? 0 : tw(tag) + 4;
            if (!tag.isEmpty()) txt(g, tag, right - tagW + 3, cy + 4, on ? fg : a(MUTED, Math.round(255 * fade)));
            txt(g, fit(m.name, right - tagW - (px + 14)), px + 14, cy + 4, fg);
            if (cy + ROW_H > viewTop && cy < viewTop + viewH)
                hits.add(new Hit(px, Math.max(cy, viewTop), PW, ROW_H - Math.max(0, viewTop - cy), 2, m, null, null));
            cy += ROW_H;
            if (m.expanded) {
                for (Setting s : m.settings) {
                    if (cy + ROW_H > viewTop && cy < viewTop + viewH) drawSetting(g, m, s, px, cy, viewTop, fade);
                    cy += ROW_H;
                }
            }
        }
        g.disableScissor();
        if (maxScroll > 0) {
            int th = Math.max(10, viewH * viewH / contentH);
            int ty = viewTop + (int) ((viewH - th) * (scroll / maxScroll));
            Ui.corners(g, px + PW - 5, ty, px + PW - 3, ty + th, 1, alpha(0x80FFFFFF, fade), true, true, true, true);
        }
    }

    private void drawSetting(GuiGraphics g, Module m, Setting s, int x, int y, int viewTop, float fade) {
        int tx = a(TEXT, Math.round(255 * fade)), mu = a(MUTED, Math.round(255 * fade));
        Ui.corners(g, x + 8, y, x + PW - 8, y + SET_H - 1, 5, alpha(0x18FFFFFF, fade), true, true, true, true);
        if (s instanceof Setting.Bool b) {
            txt(g, fit(b.name, PW - 56), x + 14, y + 4, tx);
            int bx = x + PW - 34;
            Ui.corners(g, bx, y + 3, bx + 18, y + 11, 4, alpha(b.value ? acc() : 0x55FFFFFF, fade), true, true, true, true);
            int kx = b.value ? bx + 10 : bx + 1;
            Ui.corners(g, kx, y + 4, kx + 7, y + 10, 3, alpha(b.value ? DARK : 0xFFE8ECF4, fade), true, true, true, true);
        } else if (s instanceof Setting.Num n) {
            int sx = x + 12, sw = PW - 24;
            Ui.corners(g, sx, y + 1, sx + sw, y + SET_H - 2, 4, alpha(0x30FFFFFF, fade), true, true, true, true);
            int fw = (int) (n.fraction() * sw);
            if (fw > 0) Ui.gradient(g, sx, y + 1, sx + Math.max(4, fw), y + SET_H - 2, 4, alpha(a(acc(), 0x60), fade), alpha(a(acc(), 0xC8), fade));
            String v = n.display();
            String tail = ": " + v;
            txt(g, fit(n.name, sw - 8 - tw(tail)) + tail, sx + 5, y + 4, tx);
        } else if (s instanceof Setting.Hue h) {
            String label = h.name.replaceAll("(?i)\\s+colou?r$", "");
            txt(g, label, x + 14, y + 4, tx);
            int off = Math.max(60, 14 + tw(label) + 6);
            int sx = x + off, sw = PW - off - 30;
            Ui.hueStrip(g, sx, y + 3, sx + sw, y + SET_H - 4, 3);
            int mk = sx + (int) (h.hue * sw);
            Ui.corners(g, mk - 1, y + 1, mk + 1, y + SET_H - 2, 1, alpha(0xFFFFFFFF, fade), true, true, true, true);
            Ui.corners(g, x + PW - 24, y + 3, x + PW - 14, y + SET_H - 4, 3, alpha(h.argb(255), fade), true, true, true, true);
        } else if (s instanceof Setting.Text t) {
            boolean focus = typing == t;
            Ui.corners(g, x + 12, y + 1, x + PW - 12, y + SET_H - 2, 4, alpha(0x30FFFFFF, fade), true, true, true, true);
            if (focus) Ui.outline(g, x + 12, y + 1, x + PW - 12, y + SET_H - 2, alpha(acc(), fade));
            String caret = focus && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
            if (t.value.isEmpty()) txt(g, fit(t.name, PW - 36) + caret, x + 16, y + 4, mu);
            else txt(g, fit(t.value, PW - 36) + caret, x + 16, y + 4, tx);
        } else if (s instanceof Setting.Button btn) {
            String shown = fit(btn.name, PW - 30);
            centered(g, shown, x + PW / 2f, y + 4, 1.0f, tx, false);
        }
        int hy = Math.max(y, viewTop);
        hits.add(new Hit(x, hy, PW, SET_H - (hy - y), 3, m, s, null));
    }

    // ---------------- input ----------------

    private void openPage(Category c) {
        if (page != c) { page = c; scroll = 0; scrollTarget = 0; query = ""; }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x() / uiScale, my = click.y() / uiScale;
        int button = click.button();
        typing = null;
        if (page == null && button == 0 && openT > 0.3f) {
            int seg = segmentAt(mx, my);
            if (seg >= 0) { openPage(CATS[seg]); return true; }
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(mx, my)) continue;
            switch (h.kind()) {
                case 0 -> page = null;
                case 1 -> { if (button == 0) openPage(h.cat()); }
                case 2 -> {
                    Module m = h.module();
                    if (button == 0 && !m.settings.isEmpty() && mx >= h.x() + PW - 26) m.expanded = !m.expanded;
                    else if (button == 0) m.toggle();
                    else if (button == 1 && !m.settings.isEmpty()) m.expanded = !m.expanded;
                    else if (button == 2) listening = m;
                }
                case 3 -> {
                    if (button != 0) break;
                    Setting s = h.setting();
                    if (s instanceof Setting.Bool b) b.value = !b.value;
                    else if (s instanceof Setting.Button btn) btn.action.run();
                    else if (s instanceof Setting.Text t) typing = t;
                    else if (s instanceof Setting.Num) startSlider(s, h.x() + 12, PW - 24, mx);
                    else if (s instanceof Setting.Hue hue) {
                        String label = hue.name.replaceAll("(?i)\\s+colou?r$", "");
                        int off = Math.max(60, 14 + tw(label) + 6);
                        startSlider(s, h.x() + off, PW - off - 30, mx);
                    }
                }
                default -> {}
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private void startSlider(Setting s, int x, int w, double mx) { activeSlider = s; sliderX = x; sliderW = w; applySlider(mx); }

    private void applySlider(double mx) {
        double f = (mx - sliderX) / sliderW;
        if (activeSlider instanceof Setting.Num n) n.setFraction(f);
        else if (activeSlider instanceof Setting.Hue h) h.hue = (float) Mth.clamp(f, 0.0, 0.999);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double ox, double oy) {
        if (activeSlider != null) { applySlider(click.x() / uiScale); return true; }
        return super.mouseDragged(click, ox, oy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        activeSlider = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (page != null && maxScroll > 0) { scrollSound(); scrollTarget = Mth.clamp(scrollTarget - (float) vAmount * 30f, 0, maxScroll); return true; }
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (typing != null) {
            int cp = input.codepoint();
            if (typing.value.length() < typing.maxLen && !Character.isISOControl(cp) && !Character.isWhitespace(cp)) typing.value += new String(Character.toChars(cp));
            return true;
        }
        if (listening != null || page == null) return false;
        int cp = input.codepoint();
        if (!Character.isISOControl(cp) && query.length() < 32 && !(Character.isWhitespace(cp) && query.isEmpty())) {
            query += new String(Character.toChars(cp));
            scroll = 0; scrollTarget = 0;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.input();
        if (typing != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) typing.submit();
            else if (key == GLFW.GLFW_KEY_ESCAPE) typing = null;
            else if (key == GLFW.GLFW_KEY_BACKSPACE) { if (!typing.value.isEmpty()) typing.value = typing.value.substring(0, typing.value.length() - 1); }
            else if (key == GLFW.GLFW_KEY_V && minecraft != null && minecraft.getWindow() != null
                    && (GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS)) {
                String clip = GLFW.glfwGetClipboardString(minecraft.getWindow().handle());
                if (clip != null) for (char ch : clip.toCharArray())
                    if (!Character.isISOControl(ch) && !Character.isWhitespace(ch) && typing.value.length() < typing.maxLen) typing.value += ch;
            }
            return true;
        }
        if (listening != null) {
            boolean clear = key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE;
            listening.key = clear ? -1 : key;
            listening = null;
            return true;
        }
        if (page != null && !query.isEmpty()) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) { query = query.substring(0, query.length() - 1); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE) { query = ""; return true; }
        }
        if (key == dev.kuro.client.util.MenuKey.key()) { KuroClient.modules.consumeGuiKey(); onClose(); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE && page != null) { page = null; return true; }   // back to the hub
        return super.keyPressed(input);
    }
}
