package dev.kuro.client.gui;

import dev.kuro.client.KuroClient;
import dev.kuro.client.config.ConfigManager;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Descriptions;
import dev.kuro.client.module.Module;
import dev.kuro.client.module.impl.ConfigModule;
import dev.kuro.client.module.impl.FriendsModule;
import dev.kuro.client.module.impl.Hud;
import dev.kuro.client.module.impl.SidebarUi;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.Ui;
import net.minecraft.client.renderer.RenderPipelines;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sidebar menu (turned on with the "Sidebar UI" module): a navy panel with an inset sidebar card (categories with icons,
 * then Settings / Theme / Configs / Socials / Keybinds), module rows or a 2-column grid (switch on the right, All /
 * Enabled filter chips, list / grid toggle), search at the top right. Left click a row = toggle, click the chevron or
 * right click = open its settings, middle click = bind a key. Theme / Configs / Socials open the HUD, Config and
 * Friends settings as pages. Just start typing to search every module. RIGHT SHIFT closes.
 *
 * Look: Instrument Serif / Instrument Sans fonts (util/Fonts), real PNG icons (assets/kuro/textures/gui/icons),
 * and animated gradient controls: switches with a sliding, stretching knob, sliders with a gradient fill and a round knob,
 * rows / nav items / chips that fade between hover and active states.
 */
public class SidebarGuiScreen extends Screen implements ModuleGui {
    private static final int SIDE_W = 104, HEAD_H = 52, ROW_H = 26, KEY_H = 20, GAP = 3, SET_H = 16, SLIDER_H = 22, PAD = 10, NAV_H = 13, SEARCH_W = 118, SEARCH_H = 15;

    // set every frame from the UI theme (UiThemes.navy); the values here are the Classic navy
    private int BG = 0xF0070E1C, SIDE = 0xC0050A16;
    private static final int EDGE = 0x33FFFFFF;
    private int ROW = 0xD0101A2E, ROW_HOVER = 0xE0162340, SETBG = 0xD00A1222;
    private int TEXT = 0xFFEDF2FA, MUTED = 0xFF7C8AA3, FAINT = 0xFF56627A, OFF = 0xFF1F2A44, OFF_HI = 0xFF2A3858;
    private static final int WHITE = 0xFFFFFFFF;

    // 32x32 white PNG icons (tinted when drawn): 0 combat, 1 render, 2 movement, 3 player, 4 base, 5 misc,
    // 6 settings, 7 theme, 8 configs, 9 socials, 10 keybinds
    private static final Identifier[] ICONS = new Identifier[11];
    private static final Identifier CROWN = Identifier.fromNamespaceAndPath("kuro", "textures/gui/icons/crown.png");
    static {
        for (int i = 0; i < ICONS.length; i++) ICONS[i] = Identifier.fromNamespaceAndPath("kuro", "textures/gui/icons/nav_" + i + ".png");
    }

    // static: the page you were on is still open next time the menu opens
    private static Category selCat = Category.RENDER;
    private static Module selPage;   // non-null = a settings page (Settings / Theme / Configs / Socials)
    private static int special;      // 1 = the Keybinds page
    private static boolean grid;     // module cards in two columns instead of one list
    private static boolean onlyEnabled;   // "Enabled" chip: hide modules that are off

    private record Hit(int x, int y, int w, int h, int kind, Module module, Setting setting, int ax, int aw) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private record Nav(int x, int y, int w, int h, Category cat, Module page, int special) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    /** Small header buttons: 1 = All chip, 2 = Enabled chip, 3 = list view, 4 = grid view. */
    private record Btn(int x, int y, int w, int h, int id) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    /** Key of one animated value: which object it belongs to + which of its values (0 on, 1 hover, 2 slider position ...). */
    private record AK(Object owner, int slot) {}

    private final List<Hit> hits = new ArrayList<>();
    private final List<Nav> navs = new ArrayList<>();
    private final List<Btn> btns = new ArrayList<>();
    private final Map<AK, Float> anims = new HashMap<>();
    private final Map<String, Integer> widths = new HashMap<>();

    private int px, py, pw, ph;
    private int scroll, maxScroll;
    private float scrollPos, scrollTarget;   // smooth scrolling: the wheel moves the target, the view eases toward it
    private long lastNs;
    private float frameDt = 0.016f;          // seconds since the last frame (drives every animation)
    private float openT;                     // 0 -> 1 right after the menu opens (panel eases in)
    private String hoverFull;                // full text of a shortened label, shown in a tooltip while hovering it
    private int lastMx, lastMy;
    private int acc, acc2;                   // accent colour and its gradient partner (hue shifted)
    private Setting activeSlider;
    private int sliderX, sliderW;
    private Module listening;
    private Setting.Text typing;
    private String query = "";

    public SidebarGuiScreen() {
        super(Component.literal(Hud.NAME));
    }

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

    private static int a(int argb, int alpha) { return (alpha << 24) | (argb & 0xFFFFFF); }

    private static void rr(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int c) {
        Ui.corners(g, x1, y1, x2, y2, r, c, true, true, true, true);
    }

    private static void edge(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int c) {
        int old = Ui.radius;
        Ui.radius = r;
        Ui.outline(g, x1, y1, x2, y2, c);
        Ui.radius = old;
    }

    /** A filled circle of radius r around (cx, cy). */
    private static void disc(GuiGraphics g, int cx, int cy, int r, int c) {
        rr(g, cx - r, cy - r, cx + r, cy + r, r, c);
    }

    // ---- animation: values glide toward their target instead of jumping ----

    /** Moves the stored 0..1 value of (owner, slot) toward on/off; returns it eased (smoothstep). First sight = already there. */
    private float anim(Object owner, int slot, boolean on, float speed) {
        AK k = new AK(owner, slot);
        float v = anims.getOrDefault(k, on ? 1f : 0f);
        float step = frameDt * speed;
        v = on ? Math.min(1f, v + step) : Math.max(0f, v - step);
        anims.put(k, v);
        return v * v * (3f - 2f * v);
    }

    /** Eases a value (slider position) toward its target; dragging follows faster. */
    private float follow(Object owner, int slot, float target, boolean fast) {
        AK k = new AK(owner, slot);
        float v = anims.getOrDefault(k, target);
        v += (target - v) * (1f - (float) Math.exp(-frameDt * (fast ? 34f : 16f)));
        if (Math.abs(target - v) < 0.0015f) v = target;
        anims.put(k, v);
        return v;
    }

    // ---- text: everything goes through the custom fonts ----

    private int tw(String s) {
        Integer w = widths.get(s);
        if (w == null) { if (widths.size() > 3000) widths.clear(); w = (int) Math.ceil(SdfText.width(SdfText.Face.SANS, s, SdfText.BASE)); widths.put(s, w); }
        return w;
    }

    private int twb(String s) {
        String key = "\u0001" + s;
        Integer w = widths.get(key);
        if (w == null) { if (widths.size() > 3000) widths.clear(); w = (int) Math.ceil(SdfText.width(SdfText.Face.BOLD, s, SdfText.BASE)); widths.put(key, w); }
        return w;
    }

    private void txt(GuiGraphics g, String s, int x, int y, int color) {
        SdfText.draw(g, SdfText.Face.SANS, s, x, y, SdfText.BASE, color);
    }

    private void txtB(GuiGraphics g, String s, int x, int y, int color) {
        SdfText.draw(g, SdfText.Face.BOLD, s, x, y, SdfText.BASE, color);
    }

    /** Text drawn at a fraction of the normal size (descriptions, section labels). */
    private void small(GuiGraphics g, String s, int x, int y, float sc, int color) { small(g, s, x, y, sc, color, false); }

    private void small(GuiGraphics g, String s, int x, int y, float sc, int color, boolean bold) {
        SdfText.draw(g, bold ? SdfText.Face.BOLD : SdfText.Face.SANS, s, x, y, SdfText.BASE * sc, color);
    }

    private String fit(String s, int max) { return fit(s, max, false); }

    private String fit(String s, int max, boolean bold) {
        if ((bold ? twb(s) : tw(s)) <= max) return s;
        String dots = "...";
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n).stripTrailing() + dots;
            if ((bold ? twb(t) : tw(t)) <= max) return t;
        }
        return dots;
    }

    /** The end of s that fits max pixels (so the caret side of a text box stays visible). */
    private String tail(String s, int max) {
        int n = 0;
        while (n < s.length() && tw(s.substring(n)) > max) n++;
        return s.substring(n);
    }

    private static void chevron(GuiGraphics g, int cx, int cy, boolean open, int c) {
        for (int i = 0; i < 4; i++) {
            if (open) { g.fill(cx - 3 + i, cy - 2 + i, cx - 2 + i, cy - 1 + i, c); g.fill(cx + 3 - i, cy - 2 + i, cx + 4 - i, cy - 1 + i, c); }
            else      { g.fill(cx - 2 + i, cy - 3 + i, cx - 1 + i, cy - 2 + i, c); g.fill(cx - 2 + i, cy + 3 - i, cx - 1 + i, cy + 4 - i, c); }
        }
    }

    private static String keyName(int key) {
        String n = GLFW.glfwGetKeyName(key, 0);
        return n != null ? n.toUpperCase(Locale.ROOT) : "K" + key;
    }

    private static String hueLabel(Setting.Hue h) {
        String t = h.name.replaceAll("(?i)\\s+colou?r$", "");
        return t.isEmpty() ? h.name : t;
    }

    private static Module find(Class<? extends Module> type) {
        for (Module m : KuroClient.modules.modules) if (type.isInstance(m)) return m;
        return null;
    }

    private void resetScroll() { scroll = 0; scrollPos = 0; scrollTarget = 0; }

    private void go(Category c, Module page, int sp) {
        if (c != null) selCat = c;   // settings pages keep the last category selected
        selPage = page;
        special = sp;
        resetScroll();
        query = "";
    }

    private boolean matches(Module m) {
        String q = query.toLowerCase(Locale.ROOT);
        return m.name.toLowerCase(Locale.ROOT).contains(q) || Descriptions.of(m).toLowerCase(Locale.ROOT).contains(q);
    }

    /** The modules shown in the list for the current page / search. */
    private List<Module> visible() {
        List<Module> out = new ArrayList<>();
        if (!query.isEmpty()) { for (Module m : KuroClient.modules.sorted()) if (matches(m)) out.add(m); }
        else if (selPage == null && special == 0) out.addAll(KuroClient.modules.inCategory(selCat));
        else if (special == 1) out.addAll(KuroClient.modules.sorted());
        if (onlyEnabled && (special == 0 || !query.isEmpty())) out.removeIf(m -> !m.isEnabled());
        return out;
    }

    private int searchX() { return px + pw - PAD - SEARCH_W; }
    private int searchY() { return py + 11; }

    /** Height of one settings row: sliders have a label line and a track line. */
    private static int setH(Setting s) { return s instanceof Setting.Num || s instanceof Setting.Hue ? SLIDER_H : SET_H; }

    // ---------------- drawing ----------------


    // ---- GUI scale fit: the menu is laid out in its normal size and shrunk to fit small screens / big GUI scales ----
    private float uiScale = 1f;

    private void fitScale() {
        uiScale = Mth.clamp(Math.min(width / 340f, height / 250f), 0.4f, 1f);
    }

    /** Menu-space size of the screen (the real GUI size divided by the fit scale). */
    private int vw() { return Math.round(width / uiScale); }
    private int vh() { return Math.round(height / uiScale); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        hoverSound(mx / uiScale, my / uiScale);
        fitScale();
        var mat = g.pose();
        mat.pushMatrix();
        mat.scale(uiScale, uiScale);
        float o = openT * openT * (3f - 2f * openT);          // the panel eases in from 95% size
        float os = 0.95f + 0.05f * o;
        mat.translate(vw() / 2f, vh() / 2f);
        mat.scale(os, os);
        mat.translate(-vw() / 2f, -vh() / 2f);
        try { renderScaled(g, Math.round(mx / uiScale), Math.round(my / uiScale), delta); }
        finally { mat.popMatrix(); }
    }

    private void renderScaled(GuiGraphics g, int mx, int my, float delta) {
        Hud.applyTheme();
        lastMx = mx; lastMy = my; hoverFull = null;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        frameDt = dt;
        openT = Math.min(1f, openT + dt * 6f);
        scrollPos += (scrollTarget - scrollPos) * (1f - (float) Math.exp(-dt * 18f));
        if (Math.abs(scrollTarget - scrollPos) < 0.5f) scrollPos = scrollTarget;
        scroll = Math.round(scrollPos);
        acc = UiThemes.accent();
        acc2 = UiThemes.accent2();   // gradient partner
        int[] nv = UiThemes.navy();
        BG = 0xF0000000 | nv[0]; SIDE = 0xC0000000 | nv[1]; ROW = 0xD0000000 | nv[2]; ROW_HOVER = 0xE0000000 | nv[3]; SETBG = 0xD0000000 | nv[4];
        TEXT = 0xFF000000 | nv[5]; MUTED = 0xFF000000 | nv[6]; FAINT = 0xFF000000 | nv[7]; OFF = 0xFF000000 | nv[8]; OFF_HI = 0xFF000000 | nv[9];

        pw = Mth.clamp(vw() - 30, 320, 520);
        ph = Mth.clamp(vh() - 50, 226, 290);
        px = (vw() - pw) / 2;
        py = Math.max(4, (vh() - ph) / 2);

        g.fill(0, 0, vw(), vh(), 0x40000000);   // slight dim behind the panel (stays readable with blur off)
        hits.clear(); btns.clear();

        // panel
        rr(g, px - 1, py, px + pw + 1, py + ph + 3, 11, 0x20000000);
        rr(g, px, py, px + pw, py + ph, 10, BG);
        g.fillGradient(px + 6, py + 2, px + pw - 6, py + ph / 2, a(acc, 0x1E), 0x00000000);   // soft accent glow at the top
        edge(g, px, py, px + pw, py + ph, 10, EDGE);
        // the sidebar is its own inset card
        rr(g, px + 6, py + 6, px + SIDE_W, py + ph - 6, 8, SIDE);
        edge(g, px + 6, py + 6, px + SIDE_W, py + ph - 6, 8, EDGE);

        drawSidebar(g, mx, my);
        drawHeader(g);
        drawControls(g, mx, my);
        drawList(g, mx, my);

        if (hoverFull != null) {   // full text of a label that had to be shortened
            String t = fit(hoverFull, vw() - 14);
            int tw = tw(t) + 10;
            int tx = Mth.clamp(mx + 10, 2, Math.max(2, vw() - tw - 2)), ty = Mth.clamp(my + 12, 2, vh() - 16);
            rr(g, tx, ty, tx + tw, ty + 14, 5, 0xF0121528);
            edge(g, tx, ty, tx + tw, ty + 14, 5, a(acc, 0xAA));
            txt(g, t, tx + 5, ty + 3, TEXT);
        }
    }

    private static void icon(GuiGraphics g, Identifier tex, int x, int y, int size, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, size, size, 32, 32, 32, 32, color);
    }

    private void drawSidebar(GuiGraphics g, int mx, int my) {
        navs.clear();

        // brand: crown icon + name (last word in italic serif, accent colour) + release line
        icon(g, CROWN, px + 14, py + 12, 12, acc);
        String full = Hud.NAME;
        int cut = full.lastIndexOf(' ');
        String first = cut < 0 ? full : full.substring(0, cut);
        String last = cut < 0 ? "" : full.substring(cut);
        float w1 = SdfText.width(SdfText.Face.BOLD, first, SdfText.BASE) * 0.95f, w2 = SdfText.width(SdfText.Face.SERIF_ITALIC, last, 15f) * 0.8f;
        float k = Math.min(1f, (SIDE_W - 36 - 6) / Math.max(1f, w1 + w2));
        float s1 = 0.95f * k, s2 = 0.8f * k;
        int baseline = py + 21;                       // both words share one baseline
        float bl = SdfText.BASELINE;
        SdfText.draw(g, SdfText.Face.BOLD, first, px + 30, baseline - bl * SdfText.BASE * s1, SdfText.BASE * s1, TEXT);
        SdfText.draw(g, SdfText.Face.SERIF_ITALIC, last, px + 30 + w1 * k, baseline - bl * 15f * s2, 15f * s2, acc);
        small(g, "RELEASE " + Hud.VERSION, px + 30, py + 25, 0.6f, FAINT);

        int y = py + 38;
        small(g, "MODULES", px + 16, y, 0.65f, FAINT, true);
        y += 9;
        for (Category c : Category.values()) {
            boolean act = selPage == null && special == 0 && query.isEmpty() && selCat == c;
            int count = SidebarUi.counts() ? KuroClient.modules.inCategory(c).size() : -1;
            int icon = switch (c) { case COMBAT -> 0; case RENDER -> 1; case MOVEMENT -> 2; case PLAYER -> 3; case BASE -> 4; default -> 5; };
            navRow(g, mx, my, y, c.label, count, act, icon, c);
            navs.add(new Nav(px + 10, y, SIDE_W - 14, NAV_H, c, null, 0));
            y += NAV_H + 1;
        }
        y += 4;
        small(g, "GENERAL", px + 16, y, 0.65f, FAINT, true);
        y += 9;
        String[] labels = {"Settings", "Theme", "Configs", "Socials", "Keybinds"};
        Module[] pages = {find(SidebarUi.class), find(Hud.class), find(ConfigModule.class), find(FriendsModule.class), null};
        for (int i = 0; i < labels.length; i++) {
            boolean keys = i == 4;
            if (!keys && pages[i] == null) continue;
            boolean act = query.isEmpty() && (keys ? special == 1 : selPage == pages[i]);
            navRow(g, mx, my, y, labels[i], -1, act, 6 + i, labels[i]);
            navs.add(new Nav(px + 10, y, SIDE_W - 14, NAV_H, null, pages[i], keys ? 1 : 0));
            y += NAV_H + 1;
        }
    }

    private void navRow(GuiGraphics g, int mx, int my, int y, String label, int count, boolean active, int icon, Object animKey) {
        int x1 = px + 10, x2 = px + SIDE_W - 4;
        boolean hover = mx >= x1 && mx < x2 && my >= y && my < y + NAV_H;
        float at = anim(animKey, 0, active, 9f), ht = anim(animKey, 1, hover, 12f);
        if (ht > 0.01f && at < 0.99f) rr(g, x1, y, x2, y + NAV_H, 5, a(WHITE, Math.round(0x16 * ht * (1f - at))));
        if (at > 0.01f) {   // the active pill fades in with a gradient
            Ui.gradient(g, x1, y, x2, y + NAV_H, 5, a(acc, Math.round(0x62 * at)), a(acc2, Math.round(0x26 * at)));
            edge(g, x1, y, x2, y + NAV_H, 5, a(acc, Math.round(0xAA * at)));
        }
        int tint = Ui.mix(Ui.mix(MUTED, TEXT, ht), WHITE, at);
        icon(g, ICONS[icon], x1 + 4, y + 1, 11, tint);
        String cs = count >= 0 ? String.valueOf(count) : "";
        int cw = cs.isEmpty() ? 0 : (int) (tw(cs) * 0.7f) + 4;
        String shown = fit(label, (int) ((x2 - x1 - 24 - cw) / 0.85f));
        small(g, shown, x1 + 19, y + 3, 0.85f, tint);
        if (!cs.isEmpty()) small(g, cs, x2 - cw - 2, y + 4, 0.7f, Ui.mix(FAINT, TEXT, at));
    }

    private void drawHeader(GuiGraphics g) {
        int x = px + SIDE_W + PAD;
        String label = !query.isEmpty() ? "Search" : special == 1 ? "Keybinds" : selPage != null ? (selPage instanceof SidebarUi ? "Menu" : navLabel(selPage)) : selCat.label;
        String suffix = !query.isEmpty() ? " Results" : special == 1 ? "" : selPage != null ? " Settings" : " Modules";
        int ty = py + 19 - Math.round(SdfText.BASELINE * 15f);   // serif baseline lands at py + 19
        float lw = SdfText.draw(g, SdfText.Face.SERIF_ITALIC, label, x, ty, 15f, acc);
        SdfText.draw(g, SdfText.Face.SERIF, suffix, x + lw, ty, 15f, TEXT);

        String sub;
        if (special == 1 && query.isEmpty()) {
            int bound = 0;
            for (Module mod : KuroClient.modules.modules) if (mod.key > 0) bound++;
            sub = bound + " bound · click a key, then press the new one (right click clears)";
        } else if (selPage != null && query.isEmpty()) sub = selPage.settings.size() + " options";
        else {
            List<Module> list = visible();
            int on = 0;
            for (Module mod : list) if (mod.isEnabled()) on++;
            sub = list.size() + " modules · " + on + " enabled";
        }
        small(g, sub, x, py + 26, 0.8f, MUTED);

        // search box
        int sx = searchX(), sy = searchY();
        float ft = anim("search", 0, !query.isEmpty(), 10f);
        rr(g, sx, sy, sx + SEARCH_W, sy + SEARCH_H, 6, 0x66000000);
        edge(g, sx, sy, sx + SEARCH_W, sy + SEARCH_H, 6, Ui.mix(EDGE, a(acc, 0xAA), ft));
        int ic = Ui.mix(MUTED, acc, ft);
        g.fill(sx + 7, sy + 4, sx + 11, sy + 5, ic);
        g.fill(sx + 7, sy + 8, sx + 11, sy + 9, ic);
        g.fill(sx + 6, sy + 5, sx + 7, sy + 8, ic);
        g.fill(sx + 11, sy + 5, sx + 12, sy + 8, ic);
        g.fill(sx + 11, sy + 8, sx + 13, sy + 10, ic);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (query.isEmpty()) {
            txt(g, "Search modules", sx + 17, sy + 4, MUTED);
        } else {
            String shown = tail(query, SEARCH_W - 36);
            txt(g, shown + (blink ? "_" : ""), sx + 17, sy + 4, TEXT);
            txt(g, "x", sx + SEARCH_W - 10, sy + 4, MUTED);
        }
    }

    /** "All / Enabled" filter chips and the list / grid switch, shown above the module list. */
    private void drawControls(GuiGraphics g, int mx, int my) {
        if (query.isEmpty() && (selPage != null || special != 0)) return;   // not on settings / keybinds pages
        int cx = px + SIDE_W + PAD, cy = py + 38;
        String[] names = {"All", "Enabled"};
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 1) == onlyEnabled;
            int w = (int) (tw(names[i]) * 0.8f) + 16;
            boolean hover = mx >= cx && mx < cx + w && my >= cy && my < cy + 13;
            float ot = anim("chip" + i, 0, on, 10f), ht = anim("chip" + i, 1, hover, 12f);
            rr(g, cx, cy, cx + w, cy + 13, 6, 0x44000000);
            if (ht > 0.01f) rr(g, cx, cy, cx + w, cy + 13, 6, a(WHITE, Math.round(0x22 * ht * (1f - ot))));
            if (ot > 0.01f) Ui.gradient(g, cx, cy, cx + w, cy + 13, 6, a(acc, Math.round(0x44 * ot)), a(acc2, Math.round(0x22 * ot)));
            edge(g, cx, cy, cx + w, cy + 13, 6, Ui.mix(EDGE, a(acc, 0xAA), ot));
            small(g, names[i], cx + 8, cy + 4, 0.8f, Ui.mix(MUTED, TEXT, Math.max(ot, ht * 0.6f)));
            btns.add(new Btn(cx, cy, w, 13, 1 + i));
            cx += w + 4;
        }
        // list / grid switch: the highlighted thumb slides between the two icons
        int vx = px + pw - PAD - 30;
        float gt = anim("view", 0, grid, 9f);
        rr(g, vx, cy, vx + 30, cy + 13, 6, 0x66000000);
        edge(g, vx, cy, vx + 30, cy + 13, 6, EDGE);
        int thumbX = vx + 1 + Math.round(15 * gt);
        Ui.gradient(g, thumbX, cy + 1, thumbX + 14, cy + 12, 5, a(acc, 0x66), a(acc2, 0x44));
        int lc = Ui.mix(TEXT, MUTED, gt), gc = Ui.mix(MUTED, TEXT, gt);
        for (int i = 0; i < 3; i++) g.fill(vx + 4, cy + 3 + i * 3, vx + 11, cy + 4 + i * 3, lc);          // list icon
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++)
            g.fill(vx + 19 + i * 4, cy + 3 + j * 4, vx + 22 + i * 4, cy + 6 + j * 4, gc);                 // grid icon
        btns.add(new Btn(vx, cy, 15, 13, 3));
        btns.add(new Btn(vx + 15, cy, 15, 13, 4));
    }

    private String navLabel(Module page) {
        if (page instanceof SidebarUi) return "Settings";
        if (page instanceof Hud) return "Theme";
        if (page instanceof ConfigModule) return "Configs";
        if (page instanceof FriendsModule) return "Socials";
        return page.name;
    }

    private void drawList(GuiGraphics g, int mx, int my) {
        int x0 = px + SIDE_W + PAD, x1 = px + pw - PAD, lw = x1 - x0;
        int viewTop = py + HEAD_H + 6, viewBot = py + ph - PAD;
        int cy = viewTop - scroll;

        g.enableScissor(x0 - 2, viewTop, x1 + 2, viewBot);
        if (selPage instanceof ConfigModule cm && query.isEmpty()) {
            cy = drawConfigSlots(g, cm, x0, cy, lw, viewTop, viewBot, mx, my);
            cy = drawSettingsBlock(g, cm, cm.extraSettings(), x0, cy, lw, viewTop, viewBot);
        } else if (selPage != null && query.isEmpty()) {
            cy = drawSettingsBlock(g, selPage, x0, cy, lw, viewTop, viewBot);
        } else if (special == 1 && query.isEmpty()) {
            for (Module m : visible()) { drawKeyRow(g, m, x0, cy, lw, viewTop, viewBot, mx, my); cy += KEY_H + 2; }
        } else {
            List<Module> list = visible();
            if (list.isEmpty()) small(g, onlyEnabled ? "No enabled modules here" : "No modules match", x0 + 4, viewTop + 6, 0.9f, MUTED);
            if (!grid) {
                for (Module m : list) {
                    drawRow(g, m, x0, cy, lw, viewTop, viewBot, mx, my);
                    cy += ROW_H + GAP;
                    if (m.expanded && !m.settings.isEmpty()) cy = drawSettingsBlock(g, m, x0 + 8, cy, lw - 8, viewTop, viewBot);
                }
            } else {
                int cw = (lw - GAP) / 2;
                for (int i = 0; i < list.size(); i += 2) {
                    Module m1 = list.get(i), m2 = i + 1 < list.size() ? list.get(i + 1) : null;
                    drawRow(g, m1, x0, cy, cw, viewTop, viewBot, mx, my);
                    if (m2 != null) drawRow(g, m2, x0 + cw + GAP, cy, cw, viewTop, viewBot, mx, my);
                    cy += ROW_H + GAP;
                    if (m1.expanded && !m1.settings.isEmpty()) cy = drawSettingsBlock(g, m1, x0 + 8, cy, lw - 8, viewTop, viewBot);
                    if (m2 != null && m2.expanded && !m2.settings.isEmpty()) cy = drawSettingsBlock(g, m2, x0 + 8, cy, lw - 8, viewTop, viewBot);
                }
            }
        }
        g.disableScissor();

        int contentH = cy + scroll - viewTop;
        int viewH = viewBot - viewTop;
        maxScroll = Math.max(0, contentH - viewH);
        scrollTarget = Mth.clamp(scrollTarget, 0, maxScroll);
        scrollPos = Mth.clamp(scrollPos, 0, maxScroll);
        scroll = Math.round(scrollPos);
        if (maxScroll > 0) {
            int th = Math.max(12, viewH * viewH / contentH);
            int ty = viewTop + (int) ((viewH - th) * (scroll / (double) maxScroll));
            Ui.gradient(g, x1 + 3, ty, x1 + 5, ty + th, 1, a(acc, 0x99), a(acc2, 0x99));
        }
    }

    /**
     * A switch: gradient track that fades between grey and the accent, and a round knob that slides across,
     * stretching in the direction of travel while it moves. t = eased 0 (off) .. 1 (on), hover = 0..1.
     */
    private void drawSwitch(GuiGraphics g, int sx, int sy, int w, int h, float t, float hover) {
        int r = h / 2;
        if (t > 0.02f) rr(g, sx - 1, sy - 1, sx + w + 1, sy + h + 1, r + 1, a(acc, Math.round(0x30 * t)));   // soft glow when on
        int offL = Ui.mix(OFF, OFF_HI, hover), offR = Ui.mix(OFF, OFF_HI, hover);
        Ui.gradient(g, sx, sy, sx + w, sy + h, r, Ui.mix(offL, acc, t), Ui.mix(offR, acc2, t));
        if (t < 0.98f) edge(g, sx, sy, sx + w, sy + h, r, a(WHITE, Math.round(0x33 * (1f - t))));

        int kd = h - 2;                                                   // knob diameter
        int stretch = Math.round(3f * (float) Math.sin(Math.PI * t));     // 0 at rest, widest mid-slide
        int travel = w - 2 - kd;
        int kx1 = sx + 1 + Math.round(travel * t) - stretch / 2;
        kx1 = Mth.clamp(kx1, sx + 1, sx + w - 1 - kd - stretch);
        int kx2 = kx1 + kd + stretch;
        rr(g, kx1, sy + 2, kx2, sy + 2 + kd, kd / 2, 0x40000000);          // shadow
        rr(g, kx1, sy + 1, kx2, sy + 1 + kd, kd / 2, Ui.mix(0xFFC3CCE0, WHITE, t));
    }

    /** A slider: label and value on top, a thin gradient track below with a round ringed knob that grows on hover / drag. */
    private void drawSliderTrack(GuiGraphics g, int ax, int aw, int y, float frac, float hot, boolean rainbow, int knobFill) {
        int ty = y + 14;                         // track top; the track is 4 px tall, its centre line is ty + 2
        int cy = ty + 2;
        if (rainbow) {
            for (int i = 0; i < aw; i++) {
                int inset = (i == 0 || i == aw - 1) ? 1 : 0;
                g.fill(ax + i, ty + inset, ax + i + 1, ty + 4 - inset, 0xFF000000 | Mth.hsvToRgb(i / (float) aw, 1f, 1f));
            }
        } else {
            rr(g, ax, ty, ax + aw, ty + 4, 2, OFF);
            int cx = ax + Math.round(frac * aw);
            if (cx - ax > 0) Ui.gradient(g, ax, ty, Math.max(cx, ax + 4), ty + 4, 2, Ui.mix(acc, 0xFF0B1630, 0.35f), acc2);
        }
        int cx = ax + Math.round(frac * aw);
        int kr = 4 + Math.round(hot);            // knob radius 4, 5 when hovered / dragged
        if (hot > 0.05f) disc(g, cx, cy, kr + 3, a(rainbow ? knobFill : acc, Math.round(0x38 * hot)));   // halo
        disc(g, cx, cy + 1, kr, 0x50000000);                                                               // shadow
        disc(g, cx, cy, kr, rainbow ? WHITE : acc2);                                                       // ring
        disc(g, cx, cy, kr - 2, rainbow ? knobFill : WHITE);                                               // centre
    }

    private void drawRow(GuiGraphics g, Module m, int x, int y, int w, int viewTop, int viewBot, int mx, int my) {
        if (y + ROW_H < viewTop || y > viewBot) return;
        boolean hover = mx >= x && mx < x + w && my >= Math.max(y, viewTop) && my < Math.min(y + ROW_H, viewBot);
        boolean on = m.isEnabled();
        float ot = anim(m, 0, on, 6f), ht = anim(m, 1, hover, 12f);
        rr(g, x, y, x + w, y + ROW_H, 6, Ui.mix(ROW, ROW_HOVER, ht));
        if (ot > 0.01f) Ui.gradient(g, x, y, x + w, y + ROW_H, 6, a(acc, Math.round(0x2C * ot)), a(acc2, 0x00));   // accent wash from the left
        edge(g, x, y, x + w, y + ROW_H, 6, Ui.mix(0x1CFFFFFF, a(acc, 0x77), ot));

        // switch (right), chevron + key tag to its left
        int swW = 24, swH = 12, sx = x + w - 10 - swW, sy = y + (ROW_H - swH) / 2;
        drawSwitch(g, sx, sy, swW, swH, ot, ht);

        int right = sx - 8, chevAx = 0, chevAw = 0;
        if (!m.settings.isEmpty()) {
            chevron(g, sx - 12, y + ROW_H / 2, m.expanded, Ui.mix(MUTED, TEXT, m.expanded ? 1f : ht));
            chevAx = sx - 22; chevAw = 20;
            right = sx - 24;
        }
        String tag = listening == m ? "..." : m.key > 0 ? "[" + keyName(m.key) + "]" : "";
        if (!tag.isEmpty()) {
            int tw = (int) (tw(tag) * 0.8f);
            small(g, tag, right - tw, y + ROW_H / 2 - 3, 0.8f, MUTED);
            right -= tw + 6;
        }

        String desc = SidebarUi.descriptions() ? Descriptions.of(m) : "";
        int textMax = right - (x + 10);
        String shownName = fit(m.name, textMax, true);
        String shownDesc = desc.isEmpty() ? "" : fit(desc, (int) (textMax / 0.75f));
        int nameCol = Ui.mix(TEXT, WHITE, ot);
        if (desc.isEmpty()) {
            txtB(g, shownName, x + 10, y + (ROW_H - 8) / 2, nameCol);
        } else {
            txtB(g, shownName, x + 10, y + 5, nameCol);
            small(g, shownDesc, x + 10, y + 16, 0.75f, MUTED);
        }
        boolean cutName = !shownName.equals(m.name), cutDesc = !desc.isEmpty() && !shownDesc.equals(desc);
        if (hover && (cutName || cutDesc)) hoverFull = cutName && cutDesc ? m.name + " - " + desc : cutName ? m.name : desc;

        int hy = Math.max(y, viewTop), hb = Math.min(y + ROW_H, viewBot);
        if (hb > hy) hits.add(new Hit(x, hy, w, hb - hy, 1, m, null, chevAx, chevAw));
    }

    /** Keybinds page: one slim row per module with its key; click the key, then press the new one. */
    private void drawKeyRow(GuiGraphics g, Module m, int x, int y, int w, int viewTop, int viewBot, int mx, int my) {
        if (y + KEY_H < viewTop || y > viewBot) return;
        boolean hover = mx >= x && mx < x + w && my >= Math.max(y, viewTop) && my < Math.min(y + KEY_H, viewBot);
        boolean wait = listening == m;
        float ht = anim(m, 1, hover, 12f), wt = anim(m, 3, wait, 10f);
        rr(g, x, y, x + w, y + KEY_H, 6, Ui.mix(ROW, ROW_HOVER, ht));
        edge(g, x, y, x + w, y + KEY_H, 6, 0x1CFFFFFF);
        txtB(g, fit(m.name, w / 2, true), x + 10, y + 6, TEXT);
        String tag = wait ? "press a key..." : m.key > 0 ? keyName(m.key) : "None";
        int tw = tw(tag) + 12, bx = x + w - tw - 8;
        rr(g, bx, y + 3, bx + tw, y + KEY_H - 3, 5, OFF);
        if (wt > 0.01f) Ui.gradient(g, bx, y + 3, bx + tw, y + KEY_H - 3, 5, a(acc, Math.round(0x66 * wt)), a(acc2, Math.round(0x44 * wt)));
        edge(g, bx, y + 3, bx + tw, y + KEY_H - 3, 5, Ui.mix(0x22FFFFFF, a(acc, 0xAA), wt));
        txt(g, tag, bx + 6, y + 6, wait || m.key > 0 ? TEXT : MUTED);
        int hy = Math.max(y, viewTop), hb = Math.min(y + KEY_H, viewBot);
        if (hb > hy) hits.add(new Hit(x, hy, w, hb - hy, 3, m, null, 0, 0));
    }

    /** A dark box holding all of a module's settings. Returns the y just below it. */
    private int drawSettingsBlock(GuiGraphics g, Module m, int x, int y, int w, int viewTop, int viewBot) {
        return drawSettingsBlock(g, m, m.settings, x, y, w, viewTop, viewBot);
    }

    private int drawSettingsBlock(GuiGraphics g, Module m, List<Setting> list, int x, int y, int w, int viewTop, int viewBot) {
        if (list.isEmpty()) return y;
        int h = 6;
        for (Setting s : list) h += setH(s);
        if (y + h >= viewTop && y <= viewBot) {
            rr(g, x, y, x + w, y + h, 6, SETBG);
            int sy = y + 3;
            for (Setting s : list) {
                int sh = setH(s);
                if (sy + sh > viewTop && sy < viewBot) drawSetting(g, m, s, x + 4, sy, w - 8, sh, viewTop, viewBot);
                sy += sh;
            }
        }
        return y + h + GAP;
    }

    private void drawSetting(GuiGraphics g, Module m, Setting s, int x, int y, int w, int h, int viewTop, int viewBot) {
        boolean over = lastMx >= x && lastMx < x + w && lastMy >= Math.max(y, viewTop) && lastMy < Math.min(y + h, viewBot);
        int ax = 0, aw = 0;
        if (s instanceof Setting.Bool b) {
            String sh = fit(b.name, w - 40);
            txt(g, sh, x + 4, y + 4, TEXT);
            if (over && !sh.equals(b.name)) hoverFull = b.name;
            float t = anim(s, 0, b.value, 7f), ht = anim(s, 1, over, 12f);
            drawSwitch(g, x + w - 26, y + 3, 20, 10, t, ht);
        } else if (s instanceof Setting.Num n) {
            ax = x + 7; aw = w - 14;
            String v = n.display();
            boolean drag = activeSlider == s;
            float hot = anim(s, 1, over || drag, 12f);
            String sh = fit(n.name, aw - tw(v) - 10);
            txt(g, sh, x + 4, y + 3, TEXT);
            txt(g, v, x + w - 4 - tw(v), y + 3, Ui.mix(MUTED, acc2, hot));
            if (over && !sh.equals(n.name)) hoverFull = n.name + ": " + v;
            float frac = follow(s, 2, (float) Mth.clamp(n.fraction(), 0.0, 1.0), drag);
            drawSliderTrack(g, ax, aw, y, frac, hot, false, 0);
        } else if (s instanceof Setting.Hue h2) {
            ax = x + 7; aw = w - 14;
            boolean drag = activeSlider == s;
            float hot = anim(s, 1, over || drag, 12f);
            txt(g, hueLabel(h2), x + 4, y + 3, TEXT);
            rr(g, x + w - 20, y + 2, x + w - 6, y + 12, 3, h2.argb(255));
            edge(g, x + w - 20, y + 2, x + w - 6, y + 12, 3, 0x55FFFFFF);
            float frac = follow(s, 2, h2.hue, drag);
            drawSliderTrack(g, ax, aw, y, frac, hot, true, h2.argb(255));
        } else if (s instanceof Setting.Text t) {
            boolean focus = typing == t;
            float ft = anim(s, 0, focus, 10f);
            rr(g, x + 2, y + 1, x + w - 2, y + h - 1, 4, OFF);
            edge(g, x + 2, y + 1, x + w - 2, y + h - 1, 4, a(acc, Math.round(0xCC * ft)));
            String caret = focus && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
            if (t.value.isEmpty()) {
                txt(g, fit(t.name, w - 20), x + 7, y + 4, MUTED);
                if (!caret.isEmpty()) txt(g, caret, x + 7, y + 4, TEXT);
            } else {
                txt(g, tail(t.value, w - 20) + caret, x + 7, y + 4, TEXT);
            }
        } else if (s instanceof Setting.Button btn) {
            float ht = anim(s, 1, over, 12f);
            rr(g, x + 2, y + 1, x + w - 2, y + h - 1, 4, Ui.mix(ROW, ROW_HOVER, ht));
            if (ht > 0.01f) edge(g, x + 2, y + 1, x + w - 2, y + h - 1, 4, a(acc, Math.round(0x66 * ht)));
            String shown = fit(btn.name, w - 16);
            txt(g, shown, x + (w - tw(shown)) / 2, y + 4, TEXT);
            if (over && !shown.equals(btn.name)) hoverFull = btn.name;
        }
        int hy = Math.max(y, viewTop), hb = Math.min(y + h, viewBot);
        if (hb > hy) hits.add(new Hit(x, hy, w, hb - hy, 2, m, s, ax, aw));
    }

    // ---------------- Configs page: the config manager (ConfigManager.SLOTS slots) ----------------

    private static final java.time.format.DateTimeFormatter SLOT_DATE = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm");
    private static final String[] SLOT_BTN = {"Save", "Load", "Delete"};
    private static final int SLOT_H = 32, SLOT_BW = 29;

    private int confirmSlot = -1, confirmAction = -1;   // a Save over an existing config / a Delete asks for a second click
    private long confirmUntil;
    private String cfgStatus = "";
    private long cfgStatusAt;

    private void cfgSay(String text) { cfgStatus = text; cfgStatusAt = System.currentTimeMillis(); }

    private void slotAction(ConfigModule cm, int i, int action) {
        long now = System.currentTimeMillis();
        ConfigManager.SlotInfo info = ConfigManager.slot(i);
        if (action == 3) {                       // click the name: type a new one, Enter to keep it
            Setting.Text box = cm.renameBox(i);
            box.value = "";
            typing = box;
            return;
        }
        if ((action == 1 || action == 2) && !info.used()) { cfgSay("Slot " + (i + 1) + " is empty"); return; }
        boolean needsConfirm = action == 2 || (action == 0 && info.used());
        boolean armed = confirmSlot == i && confirmAction == action && now < confirmUntil;
        if (needsConfirm && !armed) {
            confirmSlot = i; confirmAction = action; confirmUntil = now + 3000;
            cfgSay(action == 2 ? "Click Delete again to delete \"" + info.name() + "\"" : "Click Save again to overwrite \"" + info.name() + "\"");
            return;
        }
        confirmSlot = -1; confirmAction = -1;
        switch (action) {
            case 0 -> {
                String ok = cm.takePendingName(i);
                cfgSay(ConfigManager.saveSlot(i, ok) ? "Saved " + ConfigManager.slot(i).name() + " to slot " + (i + 1) : "Couldn't save slot " + (i + 1));
            }
            case 1 -> cfgSay(ConfigManager.loadSlot(i) ? "Loaded " + info.name() : "Couldn't load " + info.name());
            default -> cfgSay(ConfigManager.deleteSlot(i) ? "Deleted " + info.name() : "Couldn't delete slot " + (i + 1));
        }
    }

    private int drawConfigSlots(GuiGraphics g, ConfigModule cm, int x, int y, int w, int viewTop, int viewBot, int mx, int my) {
        long now = System.currentTimeMillis();
        boolean statusOn = !cfgStatus.isEmpty() && now - cfgStatusAt < 5000;
        if (y + 12 >= viewTop && y <= viewBot)
            small(g, statusOn ? fit(cfgStatus, (int) (w / 0.8f)) : "Save up to " + ConfigManager.SLOTS + " configs. Click a name to rename.",
                    x + 2, y + 2, 0.8f, statusOn ? acc2 : MUTED);
        y += 14;
        for (int i = 0; i < ConfigManager.SLOTS; i++) {
            drawSlotCard(g, cm, i, x, y, w, viewTop, viewBot, mx, my, now);
            y += SLOT_H + GAP;
        }
        return y + 2;
    }

    private void drawSlotCard(GuiGraphics g, ConfigModule cm, int i, int x, int y, int w, int viewTop, int viewBot, int mx, int my, long now) {
        if (y + SLOT_H < viewTop || y > viewBot) return;
        ConfigManager.SlotInfo info = ConfigManager.slot(i);
        boolean active = ConfigManager.activeSlot() == i;
        boolean hover = mx >= x && mx < x + w && my >= Math.max(y, viewTop) && my < Math.min(y + SLOT_H, viewBot);
        float ht = anim("cfgcard" + i, 1, hover, 12f), at = anim("cfgcard" + i, 0, active, 8f);
        rr(g, x, y, x + w, y + SLOT_H, 7, Ui.mix(ROW, ROW_HOVER, ht));
        if (at > 0.01f) Ui.gradient(g, x, y, x + w, y + SLOT_H, 7, a(acc, Math.round(0x1E * at)), a(acc2, Math.round(0x0C * at)));
        edge(g, x, y, x + w, y + SLOT_H, 7, Ui.mix(0x1CFFFFFF, a(acc, 0xAA), at));

        // number badge
        int bx = x + 6, by = y + (SLOT_H - 16) / 2;
        if (info.used()) Ui.gradient(g, bx, by, bx + 16, by + 16, 6, a(acc, 0xAA), a(acc2, 0x88));
        else rr(g, bx, by, bx + 16, by + 16, 6, OFF);
        String num = String.valueOf(i + 1);
        txtB(g, num, bx + (16 - tw(num)) / 2, by + 4, info.used() ? WHITE : MUTED);

        // buttons (right side)
        int btnsW = 3 * SLOT_BW + 2 * 3;
        int btnX = x + w - btnsW - 6, btnY = y + (SLOT_H - 15) / 2;
        for (int bi = 0; bi < 3; bi++) {
            int bxa = btnX + bi * (SLOT_BW + 3);
            boolean enabled = bi == 0 || info.used();
            boolean over = enabled && mx >= bxa && mx < bxa + SLOT_BW && my >= Math.max(btnY, viewTop) && my < Math.min(btnY + 15, viewBot);
            boolean armed = confirmSlot == i && confirmAction == bi && now < confirmUntil;
            float bt = anim("cfgbtn" + i + "_" + bi, 1, over, 12f), ct = anim("cfgbtn" + i + "_" + bi, 0, armed, 14f);
            rr(g, bxa, btnY, bxa + SLOT_BW, btnY + 15, 5, Ui.mix(OFF, OFF_HI, bt));
            if (ct > 0.01f) Ui.gradient(g, bxa, btnY, bxa + SLOT_BW, btnY + 15, 5, a(acc, Math.round(0x88 * ct)), a(acc2, Math.round(0x55 * ct)));
            edge(g, bxa, btnY, bxa + SLOT_BW, btnY + 15, 5, Ui.mix(0x22FFFFFF, a(acc, 0xCC), Math.max(bt, ct)));
            String label = armed ? "Sure?" : SLOT_BTN[bi];
            small(g, label, bxa + (SLOT_BW - (int) (tw(label) * 0.8f)) / 2, btnY + 5, 0.8f, enabled ? TEXT : FAINT);
            int hy = Math.max(btnY, viewTop), hb = Math.min(btnY + 15, viewBot);
            if (hb > hy) hits.add(new Hit(bxa, hy, SLOT_BW, hb - hy, 4, null, null, i, bi));
        }

        // name + saved time (click to rename)
        int nx = x + 28, nw = btnX - nx - 4;
        Setting.Text box = cm.renameBox(i);
        if (typing == box) {
            rr(g, nx - 2, y + 4, nx + nw, y + SLOT_H - 4, 5, OFF);
            edge(g, nx - 2, y + 4, nx + nw, y + SLOT_H - 4, 5, a(acc, 0xCC));
            String caret = (now / 500) % 2 == 0 ? "_" : "";
            if (box.value.isEmpty()) {
                txt(g, fit(info.used() ? info.name() : ConfigManager.defaultSlotName(i), nw - 12), nx + 3, y + 11, MUTED);
                if (!caret.isEmpty()) txt(g, caret, nx + 3, y + 11, TEXT);
            } else {
                txt(g, tail(box.value, nw - 12) + caret, nx + 3, y + 11, TEXT);
            }
        } else {
            String name = info.used() ? info.name() : cm.pendingName(i).isEmpty() ? "Empty" : cm.pendingName(i);
            String shown = fit(name, nw, true);
            txtB(g, shown, nx, y + 6, info.used() ? TEXT : MUTED);
            if (hover && mx < btnX && !shown.equals(name)) hoverFull = name;
            String sub = info.used()
                    ? SLOT_DATE.format(java.time.Instant.ofEpochMilli(info.savedAt()).atZone(java.time.ZoneId.systemDefault()))
                    : "Not saved yet";
            small(g, active ? "Active - " + sub : sub, nx, y + 18, 0.75f, active ? acc2 : MUTED);
        }
        int hy = Math.max(y, viewTop), hb = Math.min(y + SLOT_H, viewBot);
        if (hb > hy) hits.add(new Hit(nx, hy, nw, hb - hy, 4, null, null, i, 3));
    }

    // ---------------- input ----------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x() / uiScale, my = click.y() / uiScale;
        int button = click.button();
        typing = null;   // clicking anywhere drops text focus; clicking a text box re-focuses it in handle()
        int sx = searchX(), sy = searchY();
        if (!query.isEmpty() && mx >= sx + SEARCH_W - 16 && mx < sx + SEARCH_W && my >= sy && my < sy + SEARCH_H) {
            query = ""; resetScroll();
            return true;
        }
        for (Btn b : btns) {
            if (b.contains(mx, my)) {
                if (button == 0) {
                    switch (b.id()) { case 1 -> onlyEnabled = false; case 2 -> onlyEnabled = true; case 3 -> grid = false; default -> grid = true; }
                    resetScroll();
                }
                return true;
            }
        }
        for (Nav n : navs) {
            if (n.contains(mx, my)) {
                if (button == 0) go(n.cat(), n.page(), n.special());
                return true;
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains(mx, my)) { handle(h, button, mx); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    private void handle(Hit h, int button, double mx) {
        if (h.kind() == 4) {   // Configs page: slot card (ax = slot, aw = 0 save, 1 load, 2 delete, 3 rename)
            if (button == 0 && selPage instanceof ConfigModule cm) slotAction(cm, h.ax(), h.aw());
            return;
        }
        if (h.kind() == 3) {   // Keybinds page row
            if (button == 0) listening = h.module();
            else { h.module().key = -1; listening = null; }
            return;
        }
        if (h.kind() == 1) {
            Module m = h.module();
            boolean hasSettings = !m.settings.isEmpty();
            if (button == 0 && hasSettings && mx >= h.ax() && mx < h.ax() + h.aw()) m.expanded = !m.expanded;   // chevron
            else if (button == 0) m.toggle();
            else if (button == 1 && hasSettings) m.expanded = !m.expanded;
            else if (button == 2) listening = m;
            return;
        }
        if (button != 0) return;
        Setting s = h.setting();
        if (s instanceof Setting.Bool b) b.value = !b.value;
        else if (s instanceof Setting.Button btn) btn.action.run();
        else if (s instanceof Setting.Text t) typing = t;
        else if (s instanceof Setting.Num || s instanceof Setting.Hue) {
            activeSlider = s; sliderX = h.ax(); sliderW = Math.max(1, h.aw());
            applySlider(mx);
        }
    }

    private void applySlider(double mx) {
        double f = (mx - sliderX) / sliderW;
        if (activeSlider instanceof Setting.Num n) n.setFraction(f);
        else if (activeSlider instanceof Setting.Hue h) h.hue = (float) Mth.clamp(f, 0.0, 0.999);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        mx /= uiScale; my /= uiScale;
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            scrollSound();
            scrollTarget = Mth.clamp(scrollTarget - (float) vAmount * 30f, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (activeSlider != null) { applySlider(click.x() / uiScale); return true; }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        activeSlider = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (typing != null) {
            int cp = input.codepoint();
            if (typing.value.length() < typing.maxLen && !Character.isISOControl(cp) && !Character.isWhitespace(cp))
                typing.value += new String(Character.toChars(cp));
            return true;
        }
        if (listening != null) return false;
        String t = new String(Character.toChars(input.codepoint()));
        if (t.isBlank() && query.isEmpty()) return true;
        if (query.length() < 32 && !Character.isISOControl(input.codepoint())) {
            query += t;
            resetScroll();
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.input();
        if (typing != null) {   // a text box has focus: it gets every key (Right Shift included)
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                typing.submit();
                if (selPage instanceof ConfigModule) typing = null;   // a slot's name box closes when you confirm
            }
            else if (key == GLFW.GLFW_KEY_ESCAPE) typing = null;
            else if (key == GLFW.GLFW_KEY_BACKSPACE) { if (!typing.value.isEmpty()) typing.value = typing.value.substring(0, typing.value.length() - 1); }
            else if (key == GLFW.GLFW_KEY_V && minecraft != null && minecraft.getWindow() != null
                    && (GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS)) {
                String clip = GLFW.glfwGetClipboardString(minecraft.getWindow().handle());   // Ctrl+V
                if (clip != null) for (char ch : clip.toCharArray())
                    if (!Character.isISOControl(ch) && !Character.isWhitespace(ch) && typing.value.length() < typing.maxLen) typing.value += ch;
            }
            return true;
        }
        if (listening == null && !query.isEmpty()) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) { query = query.substring(0, query.length() - 1); resetScroll(); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE) { query = ""; resetScroll(); return true; }
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
}
