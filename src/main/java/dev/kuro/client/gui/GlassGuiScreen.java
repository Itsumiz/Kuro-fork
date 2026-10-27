package dev.kuro.client.gui;

import dev.kuro.client.KuroClient;
import dev.kuro.client.module.BlatantModules;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Descriptions;
import dev.kuro.client.module.Module;
import dev.kuro.client.module.impl.ConfigModule;
import dev.kuro.client.module.impl.FriendsModule;
import dev.kuro.client.module.impl.GlassUi;
import dev.kuro.client.module.impl.Hud;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.ShaderUi;
import dev.kuro.client.util.ShaderUi.Mode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The Glass menu (turned on with the "Glass UI" module, on by default): a floating frosted-glass panel on a navy,
 * dotted backdrop with an accent colour dock above it. Left: an icon rail (one icon per category plus a general page).
 * Middle: search and the module cards (name, key tag, switch, description). Right: the settings of the selected card.
 *
 * Everything you see is a shader quad (util/ShaderUi, tools/gen_shaders.py): glass panels with a lit rim, outlines that
 * fade round the shape, soft shadows, cursor light on the hovered card, gradient sliders, round knobs, the crescent logo,
 * floating rings and the vignette + dot grid. Everything that moves is a spring (critically damped for hover / scroll /
 * selection, slightly under-damped for switches, the opening and the sliding rail pill), integrated in small steps so it
 * stays smooth at any frame rate, and shapes are placed at fractional positions so motion never snaps to pixels.
 *
 * The panel (original size) sits right above the hotbar and XP bar (it covers the hearts, not the hotbar). The "Classic UI" button in
 * Render > Glass UI fades the glass menu out and brings back the older menu (Blob UI, Sidebar UI, or the draggable
 * windows, whichever is on). Enabling or keybinding a module marked blatant in module/BlatantModules.java asks first.
 * Left click a card = select it, its switch (or right click) = toggle,
 * the key tag (or middle click) = bind a key. Just start typing to search. RIGHT SHIFT closes.
 */
public class GlassGuiScreen extends Screen implements ModuleGui {
    // ---- layout, in menu units (the whole menu is scaled to fit the screen) ----
    private static final int PW = 500, PH = 360;
    private static final int SIDE_X = 8, SIDE_W = 104;                    // left sidebar: category buttons, search, profile
    private static final int MAIN_X = 122, MAIN_W = PW - 122 - 10;                  // main area: module cards (2 columns) or one module's settings
    private static final int HEAD_Y = 12, HEAD_H = 24;
    private static final int LIST_TOP = 44, LIST_BOT = PH - 12;
    private static final int CARD_H = 44, GAP = 7;

    private static final int WHITE = 0xFFFFFFFF;
    // text / ink colours: set per frame by palette() (dark glass by default, light-on-flag for the Israel theme)
    private int TEXT = 0xFFF0F0F0, MUTED = 0xFF8C8C8C, FAINT = 0xFF595959;
    private int HI = WHITE;                 // emphasis: hovered text, hover overlays, dividers
    private int LABEL = 0xFFCFCFCF;         // setting labels
    private int BODY = 0xFFC4C4C4;          // dialog body text
    private int ONACC = 0xFF0C0C0C;         // text / knob on top of the accent colour
    private int KNOB_OFF = 0xFFB8B8B8;      // switch knob when off
    private int KNOB = WHITE;               // slider knob
    private int popFill = 0xF2101010;       // dialog and tooltip background

    // 32x32 white PNG icons (tinted when drawn): 0 combat, 1 render, 2 movement, 3 player, 4 base, 5 misc, 6 settings, 7 theme, 8 configs, 9 socials, 10 keybinds
    private static final Identifier[] ICONS = new Identifier[11];
    static {
        for (int i = 0; i < ICONS.length; i++) ICONS[i] = Identifier.fromNamespaceAndPath("kuro", "textures/gui/icons/nav_" + i + ".png");
    }

    private static final Identifier FLOWER = Identifier.fromNamespaceAndPath("kuro", "textures/gui/flower.png");   // 512x512, transparent outside the petals

    // static: the page you were on is still open next time the menu opens
    private static Category selCat = Category.RENDER;    // null = the General page
    private static Module selMod;
    private static boolean detail;                      // true = the main area shows the settings of selMod, false = the module cards
    private static String selTitle = "";
    private static int selKind;                          // 0 module card, 1 settings page (General), 2 key binds

    /** One card in the middle column. kind: 0 = module (has a switch), 1 = a module's settings page, 2 = the key binds page. */
    private record Entry(Module module, String title, String desc, int kind) {}

    private static final int H_CARD = 1, H_SWITCH = 2, H_KEY = 3, H_SET = 4, H_SWATCH = 5, H_WARN_NO = 6, H_RAIL = 7,
            H_KEYROW = 8, H_CHIP = 9, H_CLEAR = 10, H_SEARCH = 11, H_CLOSE = 12, H_WARN_YES = 13;

    private record Hit(float x, float y, float w, float h, int kind, Entry entry, Module module, Setting setting, int idx, float ax, float aw) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private record AK(Object owner, int slot) {}

    private static final class Scroll {
        float target, max;
        final float[] st = new float[2];          // spring state: position, velocity
        float lastMove = -10f;                    // menu time of the last wheel / drag, drives the scrollbar fade
        float pos() { return st[0]; }
        void reset() { target = 0; st[0] = 0; st[1] = 0; }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final Map<AK, float[]> anims = new HashMap<>();
    private final Map<String, Float> widths = new HashMap<>();
    private final Scroll midScroll = new Scroll(), paneScroll = new Scroll();

    // ---- frame state ----
    private float uiScale = 1f;
    private int px, py;                                   // panel origin in menu units
    private float frameDt = 1f / 60f, rawDt = 1f / 60f, time;
    private long lastNs;
    private final float[] openS = {0f, 0f};               // spring state of the opening / closing animation
    private float open;                                   // 0 .. ~1.01
    private float fade;                                   // 0 .. 1 (content opacity)
    private boolean closing, leaving, swapDone;
    private final float[] viewS = {0f, 0f};               // list <-> settings cross-fade (0 = cards, 1 = a module's settings)
    private final float[] listS = {0f, 0f};               // staggered entrance of the cards (reset when the page changes)
    private final float[] paneS = {1f, 0f};               // the right pane fades / slides in when the selection changes
    private float paneT = 1f;
    private float mxS, myS;                               // smoothed mouse position (cursor light)
    private boolean mouseInit;
    private int lastMx, lastMy;
    private String hoverTip;
    private float tipX, tipY;

    // ---- colours (set once per frame from the accent) ----
    private int acc, acc2, panelFill, railFill, cardFill, cardFillHi, cardFillSel, headFill, edgeBase, offTrack, trackBg;

    // ---- interaction ----
    private Setting activeSlider;
    private float sliderX, sliderW;
    private Module listening;
    private Setting.Text typing;
    private String query = "";

    // ---- blatant-module warning (which modules: module/BlatantModules.java) ----
    private record Warn(Module module, boolean bind) {}
    private Warn warn, warnShown;                          // warn = asking right now; warnShown keeps the dialog drawn while it fades out

    public GlassGuiScreen() {
        super(Component.literal(Hud.NAME));
        GlassUi.consumeClassicRequest();   // drop a stale press from an earlier menu
        if (selMod == null && selCat != null) {
            List<Module> l = KuroClient.modules.inCategory(selCat);
            if (!l.isEmpty()) { selMod = l.get(0); selKind = 0; }
        }
    }

    // ---- UI sounds -------------------------------------------------------------------------------------------------
    private boolean closeSnd;
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

    @Override public boolean isBinding() { return listening != null || typing != null; }
    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        super.renderBackground(g, mx, my, delta);   // just the game's own menu blur of the real world, nothing drawn behind the panel
    }

    /** Right Shift (from the key poll) and Escape: fade out, then close (see tick). */
    @Override public void requestClose() { closing = true; }
    @Override public void onClose() { closing = true; }

    @Override
    public void tick() {
        if (!leaving && GlassUi.consumeClassicRequest()) { leaving = true; listening = null; warn = null; }
        if (closing && !closeSnd) closeSnd = true;
        if (closing && open < 0.04f && minecraft != null) minecraft.setScreen(null);
        if (leaving && !swapDone && open < 0.04f) {   // faded out: switch the glass menu off, ModuleManager opens the older menu this tick
            swapDone = true;
            GlassUi.leave();
        }
    }

    // ---------------- animation ----------------

    /** One critically / under-damped spring step (semi-implicit Euler in sub-steps of at most 4 ms, stable at any frame time). */
    private static float step(float[] s, float target, float omega, float zeta, float dt) {
        int n = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            float a = omega * omega * (target - s[0]) - 2f * zeta * omega * s[1];
            s[1] += a * h;
            s[0] += s[1] * h;
        }
        if (Math.abs(target - s[0]) < 0.0004f && Math.abs(s[1]) < 0.002f) { s[0] = target; s[1] = 0f; }
        return s[0];
    }

    /** Value of the spring (owner, slot) after this frame; first sight = already at the target. */
    private float spring(Object owner, int slot, float target, float omega, float zeta) {
        AK k = new AK(owner, slot);
        float[] s = anims.get(k);
        if (s == null) { s = new float[]{target, 0f}; anims.put(k, s); return target; }
        return step(s, target, omega, zeta, frameDt);
    }

    /** Hover / press style value: critically damped, never overshoots, always 0..1. */
    private float ease(Object owner, int slot, boolean on, float omega) {
        return Mth.clamp(spring(owner, slot, on ? 1f : 0f, omega, 1f), 0f, 1f);
    }

    private float velocity(Object owner, int slot) {
        float[] s = anims.get(new AK(owner, slot));
        return s == null ? 0f : s[1];
    }

    // ---------------- colour helpers ----------------

    private static int a(int argb, int alpha) { return (Mth.clamp(alpha, 0, 255) << 24) | (argb & 0xFFFFFF); }

    /** The colour with its alpha scaled by the menu's fade. */
    private int c(int argb) {
        int al = Math.round((argb >>> 24) * fade);
        return (Mth.clamp(al, 0, 255) << 24) | (argb & 0xFFFFFF);
    }

    private static int mix(int c1, int c2, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int a1 = c1 >>> 24, r1 = (c1 >> 16) & 255, g1 = (c1 >> 8) & 255, b1 = c1 & 255;
        int a2 = c2 >>> 24, r2 = (c2 >> 16) & 255, g2 = (c2 >> 8) & 255, b2 = c2 & 255;
        return (Math.round(a1 + (a2 - a1) * t) << 24) | (Math.round(r1 + (r2 - r1) * t) << 16) | (Math.round(g1 + (g2 - g1) * t) << 8) | Math.round(b1 + (b2 - b1) * t);
    }

    private static int hsv(float h, float s, float v) { return Mth.hsvToRgb(Mth.clamp(h, 0f, 0.999f), Mth.clamp(s, 0f, 1f), Mth.clamp(v, 0f, 1f)) & 0xFFFFFF; }

    private static int gray(float v) { int b = Math.round(Mth.clamp(v, 0f, 1f) * 255f); return (b << 16) | (b << 8) | b; }

    /** Every colour of the menu comes from the chosen UI theme (HUD > UI theme, see UiThemes). Classic is black and white with a white accent. */
    private void palette() {
        float o = GlassUi.opacity();
        UiThemes.UiTheme t = UiThemes.current();
        TEXT = 0xFF000000 | t.text; MUTED = 0xFF000000 | t.muted; FAINT = 0xFF000000 | t.faint; HI = 0xFF000000 | t.hi;
        LABEL = 0xFF000000 | t.label; BODY = 0xFF000000 | t.body; ONACC = 0xFF000000 | t.onAcc;
        KNOB_OFF = 0xFF000000 | t.knobOff; KNOB = 0xFF000000 | t.knob; popFill = t.pop;
        acc = 0xFF000000 | t.accent;
        acc2 = 0xFF000000 | t.accent2;
        panelFill = (Math.round(t.alpha[0] * o) << 24) | t.panel;
        railFill = (Math.round(t.alpha[1] * o) << 24) | t.rail;
        cardFill = (Math.round(t.alpha[2] * o) << 24) | t.card;
        cardFillHi = (Math.round(t.alpha[3] * o) << 24) | t.cardHi;
        cardFillSel = (Math.round(t.alpha[4] * o) << 24) | t.cardSel;
        headFill = (Math.round(t.alpha[5] * o) << 24) | t.head;
        edgeBase = 0xFF000000 | t.edge;
        offTrack = 0xFF000000 | t.offTrack;
        trackBg = 0xFF000000 | t.trackBg;
    }

    // ---------------- text ----------------

    private float tw(SdfText.Face f, String s, float size) {
        String key = f.ordinal() + ":" + size + ":" + s;
        Float w = widths.get(key);
        if (w == null) { if (widths.size() > 4000) widths.clear(); w = SdfText.width(f, s, size); widths.put(key, w); }
        return w;
    }

    private float twS(String s, float size) { return tw(SdfText.Face.SANS, s, size); }

    /** Text with its optical middle on cy (the middle of a capital letter sits 0.35 em above the baseline). */
    private float txt(GuiGraphics g, SdfText.Face f, String s, float x, float cy, float size, int color) {
        return SdfText.draw(g, f, s, x, cy - 0.35f * size, size, color);
    }

    private String fit(SdfText.Face f, String s, float size, float max) {
        if (tw(f, s, size) <= max) return s;
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n).stripTrailing() + "...";
            if (tw(f, t, size) <= max) return t;
        }
        return "...";
    }

    private String tail(String s, float size, float max) {
        int n = 0;
        while (n < s.length() && twS(s.substring(n), size) > max) n++;
        return s.substring(n);
    }

    /** Greedy word wrap into at most maxLines lines (the last one is cut with "..."). */
    private List<String> wrap(String s, float size, float max, int maxLines) {
        List<String> out = new ArrayList<>();
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String t = line.length() == 0 ? words[i] : line + " " + words[i];
            if (twS(t, size) <= max || line.length() == 0) { line = new StringBuilder(t); continue; }
            out.add(line.toString());
            line = new StringBuilder(words[i]);
            if (out.size() == maxLines - 1) {
                StringBuilder rest = new StringBuilder(line);
                for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
                out.add(fit(SdfText.Face.SANS, rest.toString(), size, max));
                return out;
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private static String keyName(int key) {
        String n = GLFW.glfwGetKeyName(key, 0);
        return n != null ? n.toUpperCase(Locale.ROOT) : "K" + key;
    }

    private static String hueLabel(Setting.Hue h) {
        String t = h.name.replaceAll("(?i)\\s+colou?r$", "");
        return t.isEmpty() ? h.name : t;
    }

    private void icon(GuiGraphics g, int idx, float x, float y, float size, int color) {
        var m = g.pose();
        m.pushMatrix();
        m.translate(x, y);
        float k = size / 32f;
        m.scale(k, k);
        g.blit(RenderPipelines.GUI_TEXTURED, ICONS[idx], 0, 0, 0f, 0f, 32, 32, 32, 32, 32, 32, color);
        m.popMatrix();
    }

    private static int categoryIcon(Category c) {
        if (c == null) return 6;
        return switch (c) { case COMBAT -> 0; case RENDER -> 1; case MOVEMENT -> 2; case PLAYER -> 3; case BASE -> 4; default -> 5; };
    }

    // ---------------- layout ----------------

    /** Size multiplier on the original menu scale (1.0 = original size; 1.25f made it bigger). Still shrunk if it would not fit between the top of the screen and the XP bar. */
    private static final float SIZE_BOOST = 1.0f;
    /** Gap (screen px) from the bottom of the screen up to the panel's bottom edge: hotbar 22 + XP bar 7 + a little air. Hearts sit above this, so the panel covers them. */
    private static final float BOTTOM_GAP = 31f;

    private void fitScale() {
        float old = Math.min(width * 0.62f / PW, (height - 20f) / (PH + 44f));
        float fitH = (height - BOTTOM_GAP - 6f) / PH;
        uiScale = Mth.clamp(Math.min(old * SIZE_BOOST, fitH), 0.4f, 5f);   // everything is shader / SDF drawn, so it stays sharp at any scale
    }

    private float vw() { return width / uiScale; }
    private float vh() { return height / uiScale; }

    private static Module find(Class<? extends Module> type) {
        for (Module m : KuroClient.modules.modules) if (type.isInstance(m)) return m;
        return null;
    }

    private Entry modEntry(Module m) { return new Entry(m, m.name, Descriptions.of(m), 0); }

    /** The pages of the General rail entry (also found by the search). */
    private List<Entry> generalEntries() {
        List<Entry> out = new ArrayList<>();
        Module menu = find(GlassUi.class), theme = find(Hud.class), cfg = find(ConfigModule.class), soc = find(FriendsModule.class);
        if (menu != null) out.add(new Entry(menu, "Menu", "Animation speed and effects", 1));
        if (theme != null) out.add(new Entry(theme, "Theme", "UI theme for every menu, colour, rounding, blur, watermark and module list", 1));
        if (cfg != null) out.add(new Entry(cfg, "Configs", "Save, load and share your settings", 1));
        if (soc != null) out.add(new Entry(soc, "Socials", "Friends list", 1));
        out.add(new Entry(null, "Key binds", "Every module and its key", 2));
        return out;
    }

    /** Search across every category, the General pages and the settings of every module. Name matches first, then description / category / setting matches. */
    private List<Entry> searchEntries() {
        String q = query.toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : generalEntries())
            if (e.title().toLowerCase(Locale.ROOT).contains(q) || e.desc().toLowerCase(Locale.ROOT).contains(q))
                out.add(new Entry(e.module(), e.title(), "General \u00b7 " + e.desc(), e.kind()));
        List<Entry> late = new ArrayList<>();
        for (Module m : KuroClient.modules.modules) {
            String desc = Descriptions.of(m), cat = m.category.label;
            String tail = desc.isEmpty() ? cat : cat + " \u00b7 " + desc;
            if (m.name.toLowerCase(Locale.ROOT).contains(q)) { out.add(new Entry(m, m.name, tail, 0)); continue; }
            if (desc.toLowerCase(Locale.ROOT).contains(q) || cat.toLowerCase(Locale.ROOT).contains(q)) { late.add(new Entry(m, m.name, tail, 0)); continue; }
            for (Setting st : m.settings)
                if (st.name.toLowerCase(Locale.ROOT).contains(q)) { late.add(new Entry(m, m.name, cat + " \u00b7 Setting: " + st.name, 0)); break; }
        }
        out.addAll(late);
        return out;
    }

    /** The cards of the middle column for the current page / search. */
    private List<Entry> entries() {
        if (!query.isEmpty()) return searchEntries();
        if (selCat == null) return generalEntries();
        List<Entry> out = new ArrayList<>();
        for (Module m : KuroClient.modules.inCategory(selCat)) out.add(modEntry(m));
        return out;
    }

    private static boolean isSelected(Entry e) {
        return e.kind() == 2 ? selKind == 2 : selKind == e.kind() && selMod == e.module();
    }

    /** Opens a card's settings in the main area (the X in its header goes back to the cards). */
    private void open(Entry e) {
        selMod = e.module();
        selKind = e.kind();
        selTitle = e.title();
        detail = true;
        paneS[0] = 0f; paneS[1] = 0f;
        paneScroll.reset();
        typing = null;
    }

    private void go(Category c) {
        selCat = c;
        query = "";
        detail = false;
        typing = null;
        midScroll.reset();
        listS[0] = 0f; listS[1] = 0f;
    }

    // ---------------- frame ----------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        if (!closing && !leaving) hoverSound(mx / uiScale, my / uiScale);
        long now = System.nanoTime();
        rawDt = lastNs == 0 ? 1f / 60f : Mth.clamp((now - lastNs) / 1e9f, 0.001f, 0.05f);
        lastNs = now;
        frameDt = rawDt * GlassUi.speed();
        time += rawDt;
        fitScale();
        palette();

        boolean out = closing || leaving;
        open = step(openS, out ? 0f : 1f, out ? 26f : 15f, out ? 1f : 0.80f, frameDt);
        fade = Mth.clamp(open * 1.25f, 0f, 1f);

        if (!mouseInit) { mxS = mx / uiScale; myS = my / uiScale; mouseInit = true; }

        var mat = g.pose();
        mat.pushMatrix();
        mat.scale(uiScale, uiScale);
        float os = 0.94f + 0.06f * open;                         // grows in (a hair of overshoot) ...
        float oy = (1f - Mth.clamp(open, 0f, 1f)) * 10f;         // ... while rising 10 px
        float cx = vw() / 2f, cy = vh() - BOTTOM_GAP / uiScale;   // grows out of the bottom edge, right above the hotbar
        mat.translate(cx, cy + oy);
        mat.scale(os, os);
        mat.translate(-cx, -cy);
        try { drawMenu(g, Math.round(mx / uiScale), Math.round(my / uiScale)); }
        finally { mat.popMatrix(); }
    }

    private void stepScroll(Scroll s) {
        s.target = Mth.clamp(s.target, 0f, s.max);
        step(s.st, s.target, 20f, 1f, frameDt);
        s.st[0] = Mth.clamp(s.st[0], 0f, s.max);
    }

    private void drawMenu(GuiGraphics g, int mx, int my) {
        int realMx = mx, realMy = my;
        if (warn != null) { mx = -10000; my = -10000; }   // the warning is modal: nothing behind it reacts to the mouse
        lastMx = mx; lastMy = my; hoverTip = null;
        hits.clear();
        px = Math.round((vw() - PW) / 2f);
        py = Math.round(vh() - BOTTOM_GAP / uiScale - PH);
        float kf = 1f - (float) Math.exp(-rawDt * 22f);          // the cursor light trails the mouse a little: silky, not twitchy
        mxS += (realMx - mxS) * kf;
        myS += (realMy - myS) * kf;
        stepScroll(midScroll);
        stepScroll(paneScroll);
        paneT = Mth.clamp(step(paneS, 1f, 16f, 1f, frameDt), 0f, 1f);

        drawPanel(g);
        drawSidebar(g, mx, my);
        step(listS, 1f, 9f, 1f, frameDt);
        boolean showDetail = detail;   // settings stay reachable while a search is active
        float v = Mth.clamp(step(viewS, showDetail ? 1f : 0f, 17f, 1f, frameDt), 0f, 1f);
        // the two views cross-fade and slide past each other; only the one that is mostly visible takes clicks
        if (v < 0.995f) { int n = hits.size(); drawList(g, mx, my, 1f - v, -v * 16f); if (v > 0.5f) trim(n); }
        if (v > 0.005f && GlassUi.israel()) {    // settings pane: white plate over the flag so its text can be read
            float sf = fade; fade = sf * v;
            ShaderUi.fancy(g, Mode.GLASS, px + MAIN_X - 4f, py + LIST_TOP - 6f, MAIN_W + 8f, LIST_BOT - LIST_TOP + 12f, 10f, c(0xF4FFFFFF));
            ShaderUi.fancy(g, Mode.GLASS_EDGE, px + MAIN_X - 4f, py + LIST_TOP - 6f, MAIN_W + 8f, LIST_BOT - LIST_TOP + 12f, 10f, c(a(edgeBase, 0x70)));
            fade = sf;
        }
        if (v > 0.005f) { int n = hits.size(); drawDetail(g, mx, my, v, (1f - v) * 16f); if (v <= 0.5f) trim(n); }
        if (hoverTip != null) drawTip(g);
        if (warn != null) { hits.clear(); warnShown = warn; }       // modal: only the dialog's own buttons (added by drawWarn) take clicks
        float wt = ease("warn", 0, warn != null, 20f);
        if (wt > 0.01f && warnShown != null) drawWarn(g, realMx, realMy, wt);
        else if (warn == null) warnShown = null;
    }

    private void trim(int n) { while (hits.size() > n) hits.remove(hits.size() - 1); }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void addHit(float x, float y, float w, float h, float vt, float vb, int kind, Entry e, Module m, Setting s, int idx, float ax, float aw) {
        float y0 = Math.max(y, vt), y1 = Math.min(y + h, vb);
        if (y1 > y0) hits.add(new Hit(x, y0, w, y1 - y0, kind, e, m, s, idx, ax, aw));
    }

    // ---------------- blatant-module warning ----------------

    /** Enabling a module: blatant ones ask first (see module/BlatantModules.java). */
    private void requestToggle(Module m) {
        if (!m.isEnabled() && BlatantModules.isBlatant(m)) { warn = new Warn(m, false); listening = null; }
        else m.toggle();
    }

    /** Keybinding a module: blatant ones ask first. */
    private void requestBind(Module m) {
        if (BlatantModules.isBlatant(m)) { warn = new Warn(m, true); listening = null; }
        else listening = m;
    }

    private void drawWarn(GuiGraphics g, int mx, int my, float wt) {
        Warn w = warnShown;
        float saved = fade;
        fade = saved * wt;
        int warnCol = 0xFFFF6B5E;
        float dw = 264f, dh = 118f, dx = px + (PW - dw) / 2f, dy = py + (PH - dh) / 2f + (1f - wt) * 8f;

        ShaderUi.fancy(g, Mode.PILL, px, py, PW, PH, 14f, c(0x99000000));                       // dims the menu behind
        ShaderUi.shadow(g, dx, dy + 6f, dw, dh, 22, c(0xC0000000));
        ShaderUi.fancy(g, Mode.GLASS, dx, dy, dw, dh, 12f, c(popFill));
        ShaderUi.fancy(g, Mode.GLASS_EDGE, dx, dy, dw, dh, 12f, c(a(warnCol, 0xB0)));

        float bx = dx + 20f, by = dy + 23f;                                                      // "!" badge
        ShaderUi.knob(g, bx, by, 9f, c(a(warnCol, 0x38)));
        ShaderUi.fancy(g, Mode.PILL_RING, bx - 9f, by - 9f, 18f, 18f, ShaderUi.PILL_R, c(a(warnCol, 0xD0)));
        txt(g, SdfText.Face.BOLD, "!", bx - twB("!", 11f) / 2f, by, 11f, c(warnCol));
        txt(g, SdfText.Face.BOLD, "Blatant module", dx + 38f, by, 10.5f, c(TEXT));

        String ask = w.bind() ? "Bind a key to it anyway?" : "Enable it anyway?";
        String body = w.module().name + " is marked as blatant: it is easy for anti-cheat and other players to detect. " + ask;
        float ty = dy + 46f;
        for (String line : wrap(body, 7.8f, dw - 32f, 3)) { txt(g, SdfText.Face.SANS, line, dx + 16f, ty, 7.8f, c(BODY)); ty += 10.5f; }

        float bw = (dw - 32f - 8f) / 2f, bh = 20f, bY = dy + dh - bh - 14f, b1 = dx + 16f, b2 = b1 + bw + 8f;
        warnButton(g, mx, my, "Cancel", b1, bY, bw, bh, false, H_WARN_NO);
        warnButton(g, mx, my, w.bind() ? "Bind anyway" : "Enable anyway", b2, bY, bw, bh, true, H_WARN_YES);
        fade = saved;
    }

    private float twB(String s, float size) { return tw(SdfText.Face.BOLD, s, size); }

    private void warnButton(GuiGraphics g, int mx, int my, String label, float x, float y, float w, float h, boolean danger, int kind) {
        boolean hov = warn != null && inside(mx, my, x, y, w, h);
        float ht = ease("warn-" + kind, 0, hov, 16f);
        int base = danger ? mix(a(0xFFFF6B5E, 0x30), a(0xFFFF6B5E, 0x70), ht) : mix(cardFill, cardFillHi, ht);
        ShaderUi.fancy(g, Mode.GLASS, x, y, w, h, 8f, c(base));
        ShaderUi.fancy(g, Mode.GLASS_EDGE, x, y, w, h, 8f, c(danger ? mix(a(0xFFFF6B5E, 0x90), a(0xFFFF6B5E, 0xF0), ht) : mix(a(edgeBase, 0x55), a(acc, 0xE0), ht)));
        txt(g, SdfText.Face.BOLD, label, x + (w - twB(label, 8.6f)) / 2f, y + h / 2f, 8.6f, c(mix(TEXT, HI, ht)));
        if (warn != null) hits.add(new Hit(x, y, w, h, kind, null, null, null, 0, 0, 0));
    }

    /** A switch: track cross-fades from dark to the accent gradient, the knob springs across (a little overshoot) and stretches while it moves. */
    private void drawSwitch(GuiGraphics g, float x, float y, float w, float h, Object owner, boolean on, float hover) {
        float t = Mth.clamp(spring(owner, 0, on ? 1f : 0f, 24f, 0.68f), -0.12f, 1.12f);
        float tc = Mth.clamp(t, 0f, 1f);
        ShaderUi.fancy(g, Mode.PILL, x, y, w, h, ShaderUi.PILL_R, c(mix(offTrack, mix(offTrack, HI, 0.10f), hover)));
        if (tc < 0.98f) ShaderUi.fancy(g, Mode.PILL_RING, x, y, w, h, ShaderUi.PILL_R, c(a(edgeBase, Math.round((0x2E + 0x1A * hover) * (1f - tc)))));
        if (tc > 0.01f) {
            ShaderUi.spot(g, x + w / 2f, y + h / 2f, w * 0.95f, h * 1.8f, 10, c(a(acc, Math.round(0x46 * tc))));
            ShaderUi.fancy(g, Mode.GRAD, x, y, w, h, ShaderUi.PILL_R, c(a(acc, Math.round(255 * tc))));
        }
        float r = h / 2f - 1.7f;
        float stretch = Mth.clamp(Math.abs(velocity(owner, 0)) * 0.9f, 0f, 3.2f);
        float kx = x + 1.7f + r + (w - 2f * (r + 1.7f)) * t;
        float kw = 2f * r + stretch;
        ShaderUi.fancy(g, Mode.PILL, kx - kw / 2f, y + h / 2f - r + 0.9f, kw, 2f * r, ShaderUi.PILL_R, c(0x55000000));
        ShaderUi.fancy(g, Mode.PILL, kx - kw / 2f, y + h / 2f - r, kw, 2f * r, ShaderUi.PILL_R, c(mix(KNOB_OFF, ONACC, tc)));
    }

    // ---------------- panel + rail ----------------

    private void drawPanel(GuiGraphics g) {
        ShaderUi.shadow(g, px, py + 12f, PW, PH, 36, c(0xB0000000));
        ShaderUi.shadow(g, px, py, PW, PH, 26, c(a(acc, 0x12)));
        ShaderUi.fancy(g, Mode.GLASS, px, py, PW, PH, 14f, c(panelFill));
        UiThemes.UiTheme th = UiThemes.current();
        switch (th.decor) {
            case HEARTS -> drawAstolfoDecor(g);
            case FLAG -> drawFlag(g);
            case FLOWER -> drawFlower(g, th);
            default -> drawAmbient(g, th);
        }
        ShaderUi.fancy(g, Mode.GLASS_EDGE, px, py, PW, PH, 14f, c(a(edgeBase, 0xB0)));
    }

    /** The flower: centred in the bottom-left quarter of the panel, big enough to bleed past the bottom edge (clipped to the panel). White in Classic, tinted by the theme otherwise. */
    private void drawFlower(GuiGraphics g, UiThemes.UiTheme th) {
        float fs = PH * 0.82f, fcx = px + PW * 0.25f, fcy = py + PH * 0.75f;
        int tint = th.custom ? 0x8CFFFFFF : th.light ? a(acc, 0x34) : mix(0xFFFFFFFF, acc, 0.40f) & 0x00FFFFFF | 0x8C000000;
        g.enableScissor(Math.round(px), Math.round(py), Math.round(px + PW), Math.round(py + PH));
        var fm = g.pose();
        fm.pushMatrix();
        fm.translate(fcx - fs / 2f, fcy - fs / 2f);
        float fk = fs / 512f;
        fm.scale(fk, fk);
        g.blit(RenderPipelines.GUI_TEXTURED, FLOWER, 0, 0, 0f, 0f, 512, 512, 512, 512, 512, 512, c(tint));
        fm.popMatrix();
        g.disableScissor();
    }

    private static float rnd(int i, int k) {
        double v = Math.sin(i * 12.9898 + k * 78.233) * 43758.5453;
        return (float) (v - Math.floor(v));
    }

    private static float frac(float v) { return v - (float) Math.floor(v); }

    /** Animated background of the non-flower themes: falling snow / petals, rising embers / bubbles, twinkling stars, a scrolling grid. Everything is clipped to the panel. */
    private void drawAmbient(GuiGraphics g, UiThemes.UiTheme th) {
        g.enableScissor(Math.round(px), Math.round(py), Math.round(px + PW), Math.round(py + PH));
        int hot = 0xFF000000 | th.accent, soft = 0xFF000000 | th.accent2;
        switch (th.decor) {
            case SNOW -> {
                for (int i = 0; i < 30; i++) {
                    float yf = frac(rnd(i, 2) + time * (0.035f + 0.04f * rnd(i, 1)));
                    float x = px + PW * frac(rnd(i, 3) + 0.03f * (float) Math.sin(time * 0.7f + i));
                    float edge = Math.min(1f, Math.min(yf, 1f - yf) * 6f);
                    ShaderUi.knob(g, x, py + PH * yf, 1f + 1.8f * rnd(i, 4), c(a(mix(0xFFFFFFFF, soft, 0.35f), Math.round((0x40 + 0x50 * rnd(i, 5)) * edge))));
                }
            }
            case PETALS -> {
                for (int i = 0; i < 18; i++) {
                    float yf = frac(rnd(i, 2) + time * (0.03f + 0.03f * rnd(i, 1)));
                    float x = px + PW * (frac(rnd(i, 3)) + 0.05f * (float) Math.sin(time * 0.9f + i * 1.7f + yf * 6f));
                    float y = py + PH * yf, ang = time * 1.2f + i, len = 2.2f + 2f * rnd(i, 4);
                    float edge = Math.min(1f, Math.min(yf, 1f - yf) * 6f);
                    float dx = (float) Math.cos(ang) * len, dy = (float) Math.sin(ang) * len * 0.6f;
                    ShaderUi.line(g, x - dx, y - dy, x + dx, y + dy, 2.6f, c(a(mix(soft, hot, rnd(i, 6)), Math.round(0x90 * edge))));
                }
            }
            case EMBERS -> {
                for (int i = 0; i < 26; i++) {
                    float u = frac(rnd(i, 2) + time * (0.05f + 0.05f * rnd(i, 1)));   // 0 = just born at the bottom, 1 = gone at the top
                    float x = px + PW * (frac(rnd(i, 3)) + 0.025f * (float) Math.sin(time * 1.3f + i * 2.1f));
                    float y = py + PH * (1f - u), r = 0.8f + 1.6f * rnd(i, 4);
                    float al = Math.min(1f, u * 8f) * (1f - u);
                    int col = mix(soft, hot, u);
                    ShaderUi.knob(g, x, y, r * 3f, c(a(col, Math.round(0x1C * al))));
                    ShaderUi.knob(g, x, y, r, c(a(col, Math.round(0xC0 * al))));
                }
            }
            case BUBBLES -> {
                for (int i = 0; i < 14; i++) {
                    float u = frac(rnd(i, 2) + time * (0.02f + 0.025f * rnd(i, 1)));
                    float x = px + PW * (frac(rnd(i, 3)) + 0.02f * (float) Math.sin(time * 0.8f + i * 1.9f));
                    float y = py + PH * (1f - u), r = 3f + 8f * rnd(i, 4);
                    float al = Math.min(1f, u * 8f) * Math.min(1f, (1f - u) * 5f);
                    ShaderUi.fancy(g, Mode.PILL_RING, x - r, y - r, 2f * r, 2f * r, ShaderUi.PILL_R, c(a(soft, Math.round(0x58 * al))));
                }
            }
            case STARS -> {
                for (int i = 0; i < 34; i++) {
                    float tw = 0.5f + 0.5f * (float) Math.sin(time * (0.8f + rnd(i, 1)) + i * 2.3f);
                    float x = px + PW * rnd(i, 3), y = py + PH * rnd(i, 4);
                    int al = Math.round(0x28 + 0x80 * tw);
                    if (i % 8 == 0) drawSparkle(g, x, y, 3.5f + 3f * rnd(i, 5) * (0.6f + 0.4f * tw), 1.1f, c(a(soft, al)));
                    else ShaderUi.knob(g, x, y, 0.7f + 1.1f * rnd(i, 5), c(a(i % 3 == 0 ? hot : 0xFFFFFFFF, al)));
                }
            }
            case GRID -> {
                float hy = py + PH * 0.60f, bh = PH * 0.40f, cx = px + PW / 2f;
                float scroll = frac(time * 0.22f);
                for (int k = 0; k < 9; k++) {
                    float f = (k + scroll) / 9f;
                    float y = hy + bh * f * f;
                    ShaderUi.line(g, px, y, px + PW, y, 1f, c(a(mix(hot, soft, f), Math.round(0x10 + 0x34 * f))));
                }
                for (int j = -6; j <= 6; j++)
                    ShaderUi.line(g, cx + j * PW * 0.018f, hy, cx + j * PW * 0.17f, py + PH, 1f, c(a(mix(hot, soft, (j + 6) / 12f), 0x2C)));
                ShaderUi.line(g, px, hy, px + PW, hy, 1.2f, c(a(hot, 0x44)));
            }
            default -> {}
        }
        g.disableScissor();
    }

    /**
     * Flag decoration (Israel theme): the panel background is the flag, in its real proportions (stripes 25.5 / 220 of the height, 25 in
     * from the top and bottom; star circle 100 / 220 of the height, centred, outline 5.5 / 220). The controls are white glass
     * with flag-blue edges; the settings pane gets a white plate (drawn elsewhere) so its text is not on the stripes.
     */
    private void drawFlag(GuiGraphics g) {
        int blue = c(0xFF0038B8);
        float k = PH / 220f, stripe = 25.5f * k, off = 25f * k;
        ShaderUi.fancy(g, Mode.PILL, px + 0.5f, py + off, PW - 1f, stripe, 0f, blue);
        ShaderUi.fancy(g, Mode.PILL, px + 0.5f, py + PH - off - stripe, PW - 1f, stripe, 0f, blue);
        drawStar(g, px + PW / 2f, py + PH / 2f, 50f * k - 2.75f * k, 5.5f * k, blue);
    }

    /** Heart outline (or filled with a few nested outlines) centred on cx, cy; size = half width. */
    private void drawHeart(GuiGraphics g, float cx, float cy, float size, float w, int col) {
        float px0 = 0, py0 = 0;
        for (int i = 0; i <= 36; i++) {
            double t = Math.PI * 2.0 * i / 36.0;
            float hx = (float) (16.0 * Math.pow(Math.sin(t), 3)) / 16f;
            float hy = -(float) (13.0 * Math.cos(t) - 5.0 * Math.cos(2 * t) - 2.0 * Math.cos(3 * t) - Math.cos(4 * t)) / 16f;
            float x = cx + hx * size, y = cy + hy * size;
            if (i > 0) ShaderUi.line(g, px0, py0, x, y, w, col);
            px0 = x; py0 = y;
        }
    }

    /** Four-point sparkle. */
    private void drawSparkle(GuiGraphics g, float cx, float cy, float r, float w, int col) {
        ShaderUi.line(g, cx - r, cy, cx + r, cy, w, col);
        ShaderUi.line(g, cx, cy - r, cx, cy + r, w, col);
        float d = r * 0.45f;
        ShaderUi.line(g, cx - d, cy - d, cx + d, cy + d, w * 0.8f, col);
        ShaderUi.line(g, cx - d, cy + d, cx + d, cy - d, w * 0.8f, col);
    }

    /** Astolfo theme background: a big faint heart bleeding off the bottom-left, small hearts and sparkles, a pink glow at the top. */
    private void drawAstolfoDecor(GuiGraphics g) {
        g.enableScissor(Math.round(px), Math.round(py), Math.round(px + PW), Math.round(py + PH));
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 1.2f);
        for (int i = 0; i < 4; i++) drawHeart(g, px + PW * 0.25f, py + PH * 0.80f, PH * (0.46f - i * 0.07f), 2.2f, c(a(0xFFFF5FA8, 0x16 + i * 4)));
        drawHeart(g, px + PW * 0.86f, py + PH * 0.86f, 26f, 1.6f, c(a(0xFFFF8CC6, 0x30)));
        drawHeart(g, px + PW * 0.62f, py + PH * 0.94f, 12f, 1.2f, c(a(0xFFFF8CC6, 0x28)));
        drawSparkle(g, px + PW * 0.90f, py + PH * 0.12f, 9f + 2f * pulse, 1.4f, c(a(0xFFFFB3D9, 0x70)));
        drawSparkle(g, px + PW * 0.72f, py + PH * 0.06f, 5f, 1.1f, c(a(0xFFFFB3D9, 0x50)));
        drawSparkle(g, px + PW * 0.30f, py + PH * 0.93f, 6f + 1.5f * (1f - pulse), 1.1f, c(a(0xFFFFB3D9, 0x50)));
        g.disableScissor();
    }

    /** Star of David: two overlapping triangles with round joins. r = radius of the points' circle (to the line centre). */
    private void drawStar(GuiGraphics g, float cx, float cy, float r, float w, int col) {
        for (int t = 0; t < 2; t++) {
            for (int i = 0; i < 3; i++) {
                double a1 = Math.toRadians((t == 0 ? -90 : 90) + i * 120), a2 = Math.toRadians((t == 0 ? -90 : 90) + (i + 1) * 120);
                float x1 = cx + (float) Math.cos(a1) * r, y1 = cy + (float) Math.sin(a1) * r, x2 = cx + (float) Math.cos(a2) * r, y2 = cy + (float) Math.sin(a2) * r;
                ShaderUi.line(g, x1, y1, x2, y2, w, col);
                ShaderUi.knob(g, x1, y1, w / 2f, col);
            }
        }
    }

    private final float[] indS = {Float.NaN, 0f};          // the sliding pill behind the active sidebar row

    private void drawSidebar(GuiGraphics g, int mx, int my) {
        float x0 = px + SIDE_X, y0 = py + 8f, w = SIDE_W, h = PH - 16f;
        ShaderUi.fancy(g, Mode.GLASS, x0, y0, w, h, 10f, c(railFill));
        ShaderUi.fancy(g, Mode.GLASS_EDGE, x0, y0, w, h, 10f, c(a(edgeBase, 0x30)));

        Category[] cats = Category.values();
        float hy = y0 + h - 8f - 22f, sh = 20f, sy = hy - 8f - sh;           // profile head, search box
        float first = y0 + 8f, stepY = (sy - 8f - first) / (cats.length + 1), rowH = stepY - 4f;   // the category bars grow to fill the space down to the search bar
        int active = !query.isEmpty() ? -1 : selCat == null ? cats.length : selCat.ordinal();
        float targetY = first + Math.max(active, 0) * stepY;
        if (Float.isNaN(indS[0])) indS[0] = targetY;
        float indY = step(indS, targetY, 22f, 0.78f, frameDt);
        float ia = ease("ind", 0, active >= 0, 14f);
        if (ia > 0.01f) ShaderUi.fancy(g, Mode.PILL, x0 + 5f, indY, w - 10f, rowH, ShaderUi.PILL_R, c(a(acc, Math.round(0xF2 * ia))));
        for (int i = 0; i <= cats.length; i++) {
            float y = first + i * stepY;
            Category cat = i < cats.length ? cats[i] : null;
            String label = cat == null ? "Settings" : cat.label;
            boolean hov = inside(mx, my, x0 + 5f, y, w - 10f, rowH);
            Object own = cat == null ? "rail-gen" : cat;
            float ht = ease(own, 1, hov, 16f), at = ease(own, 0, i == active, 12f);
            if (ht > 0.01f && at < 0.99f) ShaderUi.fancy(g, Mode.PILL, x0 + 5f, y, w - 10f, rowH, ShaderUi.PILL_R, c(a(HI, Math.round(0x16 * ht * (1f - at)))));
            int tint = mix(mix(MUTED, TEXT, ht), ONACC, at);
            icon(g, categoryIcon(cat), x0 + 13f, y + rowH / 2f - 6.5f, 13f, c(tint));
            txt(g, SdfText.Face.BOLD, label, x0 + 31f, y + rowH / 2f, 9f, c(tint));
            hits.add(new Hit(x0 + 5f, y, w - 10f, rowH, H_RAIL, null, null, null, i, 0, 0));
        }

        // profile (bottom): skin head, name, and the server you are on
        Minecraft mc = minecraft;
        drawHead(g, x0 + 8f, hy, 22f);
        String name = "Not in a world", server = "Offline";
        if (mc != null && mc.player != null) {
            name = mc.player.getName().getString();
            server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : mc.getSingleplayerServer() != null ? "Singleplayer" : "Unknown server";
        }
        float tx = x0 + 37f, max = w - 37f - 6f;
        txt(g, SdfText.Face.BOLD, fit(SdfText.Face.BOLD, name, 8.8f, max), tx, hy + 6.5f, 8.8f, c(TEXT));
        txt(g, SdfText.Face.SANS, fit(SdfText.Face.SANS, server, 7.2f, max), tx, hy + 16.5f, 7.2f, c(MUTED));

        // search (above the profile): every category, the General pages and every module's settings
        float sx = x0 + 6f, sw = w - 12f;
        boolean typingQ = !query.isEmpty();
        boolean hovS = inside(mx, my, sx, sy, sw, sh);
        float ft = ease("search", 0, typingQ, 12f), hs = ease("search", 1, hovS, 16f);
        ShaderUi.fancy(g, Mode.GLASS, sx, sy, sw, sh, 8f, c(mix(mix(headFill, cardFillHi, hs), cardFillHi, ft)));
        ShaderUi.fancy(g, Mode.GLASS_EDGE, sx, sy, sw, sh, 8f, c(mix(a(edgeBase, 0x50), a(acc, 0xD0), ft)));
        int ic = mix(MUTED, acc, ft);
        ShaderUi.fancy(g, Mode.PILL_RING, sx + 8f, sy + 5.5f, 8f, 8f, ShaderUi.PILL_R, c(ic));
        ShaderUi.line(g, sx + 15.2f, sy + 13.2f, sx + 17.9f, sy + 15.9f, 1.4f, c(ic));
        if (!typingQ) {
            txt(g, SdfText.Face.SANS, "Search", sx + 24f, sy + sh / 2f, 8.6f, c(MUTED));
        } else {
            boolean blink = (int) (time * 2f) % 2 == 0;
            txt(g, SdfText.Face.SANS, tail(query, 8.6f, sw - 46f) + (blink ? "_" : ""), sx + 24f, sy + sh / 2f, 8.6f, c(TEXT));
            float cx = sx + sw - 11f, cy = sy + sh / 2f;
            boolean hovX = inside(mx, my, sx + sw - 22f, sy, 22f, sh);
            int xc = c(mix(MUTED, HI, hovX ? 1f : 0f));
            ShaderUi.line(g, cx - 3, cy - 3, cx + 3, cy + 3, 1.3f, xc);
            ShaderUi.line(g, cx - 3, cy + 3, cx + 3, cy - 3, 1.3f, xc);
            hits.add(new Hit(sx + sw - 22f, sy, 22f, sh, H_CLEAR, null, null, null, 0, 0, 0));
        }
    }

    // ---------------- main area: module cards ----------------

    private void drawList(GuiGraphics g, int mx, int my, float alpha, float dx) {
        float savedFade = fade;
        fade = savedFade * alpha;
        var pm = g.pose();
        pm.pushMatrix();
        pm.translate(dx, 0f);
        try { drawListBody(g, mx, my); } finally { pm.popMatrix(); fade = savedFade; }
    }

    private void drawListBody(GuiGraphics g, int mx, int my) {
        List<Entry> list = entries();
        float hx = px + MAIN_X, hy = py + HEAD_Y;
        String title = !query.isEmpty() ? "Search" : selCat == null ? "Settings" : selCat.label;
        txt(g, SdfText.Face.BOLD, title, hx + 2f, hy + HEAD_H / 2f, 12f, c(TEXT));
        String count = list.size() + (list.size() == 1 ? " result" : " results");
        if (query.isEmpty()) count = list.size() + (selCat == null ? " pages" : " modules");
        txt(g, SdfText.Face.SANS, count, hx + MAIN_W - 4f - twS(count, 8f), hy + HEAD_H / 2f, 8f, c(MUTED));

        float colW = MAIN_W, viewTop = py + LIST_TOP, viewBot = py + LIST_BOT, viewH = viewBot - viewTop;
        g.enableScissor(Math.round(hx - 3f), Math.round(viewTop), Math.round(hx + MAIN_W + 3f), Math.round(viewBot));
        if (list.isEmpty()) txt(g, SdfText.Face.SANS, "No modules match", hx + 6f, viewTop + 12f, 9f, c(MUTED));
        for (int i = 0; i < list.size(); i++) {
            float x = hx, y = viewTop - midScroll.pos() + i * (CARD_H + GAP);
            if (y + CARD_H >= viewTop && y <= viewBot) {
                float t = Mth.clamp(listS[0] * 1.8f - Math.min(i, 9) * 0.11f, 0f, 1f);
                t = t * t * (3f - 2f * t);                                   // smoothstep: cards rise in one after another
                float sf = fade;
                fade = sf * t;
                drawCard(g, list.get(i), x, y + (1f - t) * 9f, colW, viewTop, viewBot, mx, my);
                fade = sf;
            }
        }
        g.disableScissor();
        int rows = list.size();
        midScroll.max = Math.max(0f, rows * (CARD_H + GAP) - GAP - viewH);
        drawScrollbar(g, midScroll, hx + MAIN_W + 1.5f, viewTop, viewH);
    }

    /** Your skin's face (+ hat layer) in a thin frame, size in menu units. Nothing is drawn outside a world. */
    private void drawHead(GuiGraphics g, float x, float y, float size) {
        Minecraft mc = minecraft;
        if (mc == null || mc.player == null) return;
        var m = g.pose();
        m.pushMatrix();
        m.translate(x, y);
        float k = size / 16f;
        m.scale(k, k);
        PlayerFaceRenderer.draw(g, mc.player.getSkin(), 0, 0, 16);
        m.popMatrix();
        ShaderUi.fancy(g, Mode.GLASS_EDGE, x - 0.5f, y - 0.5f, size + 1f, size + 1f, 5f, c(a(edgeBase, 0x90)));
    }

    private void drawScrollbar(GuiGraphics g, Scroll s, float x, float top, float viewH) {
        if (s.max <= 0.5f) return;
        float vis = Mth.clamp(1.4f - (time - s.lastMove), 0f, 1f);
        float a = ease(s, 5, vis > 0f, 8f);
        if (a < 0.02f) return;
        float contentH = viewH + s.max;
        float th = Math.max(14f, viewH * viewH / contentH);
        float ty = top + (viewH - th) * (s.pos() / s.max);
        ShaderUi.fancy(g, Mode.PILL, x, ty, 2.5f, th, ShaderUi.PILL_R, c(a(acc, Math.round(0xC0 * a))));
    }

    private void drawCard(GuiGraphics g, Entry e, float x, float y, float w, float viewTop, float viewBot, int mx, int my) {
        Module m = e.module();
        boolean sel = isSelected(e);
        float vis0 = Math.max(y, viewTop), vis1 = Math.min(y + CARD_H, viewBot);
        boolean hov = mx >= x && mx < x + w && my >= vis0 && my < vis1;
        boolean on = e.kind() == 0 && m.isEnabled();
        float ht = ease(e, 1, hov, 16f), st = ease(e, 2, sel, 14f), ot = ease(e, 3, on, 10f);

        ShaderUi.fancy(g, Mode.GLASS, x, y, w, CARD_H, 8f, c(mix(mix(cardFill, cardFillHi, ht), cardFillSel, st)));
        if (GlassUi.cursorLight() && ht > 0.01f)
            ShaderUi.cardLight(g, x, y, w, CARD_H, 8, (mxS - x) / w, (myS - y) / CARD_H, 10, Math.round(0x2C * ht));
        int edge = mix(mix(a(edgeBase, 0x52), a(edgeBase, 0x88), ht), a(acc, 0xE6), st);
        edge = mix(edge, a(acc, 0xA0), ot * 0.55f * (1f - st));
        ShaderUi.fancy(g, Mode.GLASS_EDGE, x, y, w, CARD_H, 8f, c(edge));

        hits.add(cardHit(x, y, w, viewTop, viewBot, e));
        float right = x + w - 10f;
        float swX = x + w - 12f - 24f, swY = y + (CARD_H - 12f) / 2f;
        float gearX = (e.kind() == 0 ? swX - 10f : x + w - 12f) - 13f;
        icon(g, 6, gearX, y + CARD_H / 2f - 6.5f, 13f, c(mix(MUTED, HI, Math.max(ht, st))));     // the gear: opens the settings
        right = gearX - 8f;
        if (e.kind() == 0) {
            drawSwitch(g, swX, swY, 24f, 12f, m, on, ht);
            addHit(swX - 3f, swY - 4f, 30f, 20f, viewTop, viewBot, H_SWITCH, e, m, null, 0, 0, 0);
        }

        String desc = GlassUi.descriptions() ? e.desc() : "";
        float maxW = right - (x + 12f) - 2f;
        String name = fit(SdfText.Face.BOLD, e.title(), 9.6f, maxW);
        int nameCol = mix(TEXT, HI, Math.max(st, ot));
        if (desc.isEmpty()) {
            txt(g, SdfText.Face.BOLD, name, x + 12f, y + CARD_H / 2f, 9.6f, c(nameCol));
        } else {
            txt(g, SdfText.Face.BOLD, name, x + 12f, y + 14f, 9.6f, c(nameCol));
            String shown = fit(SdfText.Face.SANS, desc, 7.4f, maxW);
            txt(g, SdfText.Face.SANS, shown, x + 12f, y + 28f, 7.4f, c(mix(MUTED, mix(MUTED, TEXT, 0.4f), Math.max(ht, st))));
            if (hov && !shown.equals(desc)) { hoverTip = desc.length() > 70 ? desc.substring(0, 68) + "..." : desc; tipX = mx + 12f; tipY = my + 14f; }
        }
        if (hov && !name.equals(e.title()) && hoverTip == null) { hoverTip = e.title(); tipX = mx + 12f; tipY = my + 14f; }
    }

    private Hit cardHit(float x, float y, float w, float viewTop, float viewBot, Entry e) {
        float y0 = Math.max(y, viewTop), y1 = Math.min(y + CARD_H, viewBot);
        return new Hit(x, y0, w, Math.max(0f, y1 - y0), H_CARD, e, e.module(), null, 0, 0, 0);
    }

    // ---------------- main area: one module's settings ----------------

    private void drawDetail(GuiGraphics g, int mx, int my, float alpha, float dx) {
        float savedFade = fade;
        fade = savedFade * alpha;
        var pm = g.pose();
        pm.pushMatrix();
        pm.translate(dx, 0f);
        try { drawDetailBody(g, mx, my); } finally { pm.popMatrix(); fade = savedFade; }
    }

    private void drawDetailBody(GuiGraphics g, int mx, int my) {
        float hx = px + MAIN_X, hy = py + HEAD_Y, hw = MAIN_W;
        txt(g, SdfText.Face.BOLD, fit(SdfText.Face.BOLD, selTitle, 12f, hw - 40f), hx + 2f, hy + HEAD_H / 2f, 12f, c(TEXT));
        // close button (round, top right)
        float cr = 9f, ccx = hx + hw - cr - 2f, ccy = hy + HEAD_H / 2f;
        boolean hovC = inside(mx, my, ccx - cr, ccy - cr, cr * 2f, cr * 2f);
        float ht = ease("close", 0, hovC, 16f);
        ShaderUi.knob(g, ccx, ccy, cr, c(mix(cardFillHi, cardFillSel, ht)));
        int xc = c(mix(MUTED, HI, ht));
        ShaderUi.line(g, ccx - 3.2f, ccy - 3.2f, ccx + 3.2f, ccy + 3.2f, 1.4f, xc);
        ShaderUi.line(g, ccx - 3.2f, ccy + 3.2f, ccx + 3.2f, ccy - 3.2f, 1.4f, xc);
        hits.add(new Hit(ccx - cr, ccy - cr, cr * 2f, cr * 2f, H_CLOSE, null, null, null, 0, 0, 0));
        ShaderUi.fancy(g, Mode.PILL, hx, py + LIST_TOP - 5f, hw, 1f, 0f, c(a(HI, 0x1A)));

        float viewTop = py + LIST_TOP, viewBot = py + LIST_BOT, viewH = viewBot - viewTop;
        float saved = fade;
        float x = hx + 2f, w = hw - 6f;
        float y = viewTop - paneScroll.pos();
        g.enableScissor(Math.round(hx - 3f), Math.round(viewTop), Math.round(hx + hw + 3f), Math.round(viewBot));
        float end;
        if (selKind == 2) end = drawKeyRows(g, x, y, w, viewTop, viewBot, mx, my);
        else if (selMod != null) end = drawModulePane(g, x, y, w, viewTop, viewBot, mx, my);
        else { txt(g, SdfText.Face.SANS, "Select a module", x + 4f, y + 12f, 9f, c(MUTED)); end = y + 24f; }
        g.disableScissor();
        fade = saved;
        paneScroll.max = Math.max(0f, end + paneScroll.pos() - viewTop - viewH);
        drawScrollbar(g, paneScroll, hx + hw - 1f, viewTop, viewH);
    }

    private float drawKeyRows(GuiGraphics g, float x, float y, float w, float vt, float vb, int mx, int my) {
        for (Module m : KuroClient.modules.sorted()) {
            if (y + 22f >= vt && y <= vb) {
                boolean wait = listening == m;
                boolean hov = inside(mx, my, x, Math.max(y, vt), w, Math.min(y + 22f, vb) - Math.max(y, vt));
                float ht = ease(m, 6, hov, 16f), wt = ease(m, 7, wait, 12f);
                ShaderUi.fancy(g, Mode.GLASS, x, y, w, 20f, 7f, c(mix(cardFill, cardFillHi, ht)));
                ShaderUi.fancy(g, Mode.GLASS_EDGE, x, y, w, 20f, 7f, c(mix(a(edgeBase, 0x40), a(acc, 0xD0), wt)));
                txt(g, SdfText.Face.BOLD, fit(SdfText.Face.BOLD, m.name, 8.8f, w * 0.55f), x + 9f, y + 10f, 8.8f, c(TEXT));
                String tag = wait ? "press a key..." : m.key > 0 ? keyName(m.key) : "n/a";
                float tgw = twS(tag, 8f) + 12f;
                ShaderUi.fancy(g, Mode.PILL, x + w - tgw - 5f, y + 3f, tgw, 14f, ShaderUi.PILL_R, c(mix(offTrack, acc, wt * 0.6f)));
                txt(g, SdfText.Face.SANS, tag, x + w - tgw - 5f + 6f, y + 10f, 8f, c(wait || m.key > 0 ? TEXT : MUTED));
                addHit(x, y, w, 20f, vt, vb, H_KEYROW, null, m, null, 0, 0, 0);
            }
            y += 23f;
        }
        return y;
    }

    private float drawModulePane(GuiGraphics g, float x, float y, float w, float vt, float vb, int mx, int my) {
        Module m = selMod;
        Entry fake = new Entry(m, m.name, "", selKind);
        String desc = Descriptions.of(m);
        if (!desc.isEmpty() && selKind == 0) {
            for (String line : wrap(desc, 7.8f, w - 8f, 3)) {
                if (y + 10f >= vt && y <= vb) txt(g, SdfText.Face.SANS, line, x + 4f, y + 5f, 7.8f, c(MUTED));
                y += 10.5f;
            }
            y += 6f;
        }
        if (selKind == 0) {
            float rh = 28f;
            if (y + rh >= vt && y <= vb) {
                boolean wait = listening == m;
                String tag = wait ? "press a key..." : m.key > 0 ? keyName(m.key) : "None";
                float tgw = twS(tag, 8f) + 14f, swX = x + w - 4f - 24f, bx = swX - 8f - tgw;
                boolean hov = inside(mx, my, bx, y + 6f, tgw, 16f);
                float ht = ease(fake, 8, hov, 16f), wt = ease(fake, 9, wait, 12f);
                txt(g, SdfText.Face.SANS, "Enabled", x + 4f, y + rh / 2f, 9.2f, c(TEXT));
                ShaderUi.fancy(g, Mode.GLASS, bx, y + 6f, tgw, 16f, 8f, c(mix(mix(cardFill, cardFillHi, ht), cardFillSel, wt)));
                ShaderUi.fancy(g, Mode.GLASS_EDGE, bx, y + 6f, tgw, 16f, 8f, c(mix(a(edgeBase, 0x55), a(acc, 0xE0), wt)));
                txt(g, SdfText.Face.SANS, tag, bx + 7f, y + rh / 2f, 8f, c(wait || m.key > 0 ? TEXT : MUTED));
                addHit(bx, y + 6f, tgw, 16f, vt, vb, H_KEY, null, m, null, 0, 0, 0);
                drawSwitch(g, swX, y + (rh - 12f) / 2f, 24f, 12f, m, m.isEnabled(), ease(m, 4, inside(mx, my, swX - 3f, y, 30f, rh), 16f));
                addHit(swX - 3f, y + 4f, 30f, 20f, vt, vb, H_SWITCH, null, m, null, 0, 0, 0);
            }
            y += rh;
            ShaderUi.fancy(g, Mode.PILL, x, y + 1f, w, 1f, 0f, c(a(HI, 0x14)));
            y += 8f;
        }
        if (!m.settings.isEmpty() && selKind == 0) {
            txt(g, SdfText.Face.SANS, "General", x + 4f, y + 6f, 7.6f, c(FAINT));
            y += 14f;
        }
        if (m.settings.isEmpty()) {
            txt(g, SdfText.Face.SANS, "This module has no settings", x + 4f, y + 8f, 8.6f, c(FAINT));
            return y + 20f;
        }
        for (Setting s : m.settings) y += drawSetting(g, m, s, x, y, w, vt, vb, mx, my);
        return y + 4f;
    }

    private float drawBoolRow(GuiGraphics g, float x, float y, float w, float vt, float vb, int mx, int my, String label, Object owner, boolean value, boolean moduleToggle, Object hitOwner) {
        float h = 26f;
        if (y + h >= vt && y <= vb) {
            float y0 = Math.max(y, vt), y1 = Math.min(y + h, vb);
            boolean hov = inside(mx, my, x, y0, w, y1 - y0);
            float ht = ease(owner, 4, hov, 16f);
            if (ht > 0.01f) ShaderUi.fancy(g, Mode.PILL, x - 2f, y + 1f, w + 4f, h - 2f, 7f, c(a(HI, Math.round(0x0E * ht))));
            txt(g, SdfText.Face.SANS, label, x + 4f, y + h / 2f, 8.8f, c(mix(LABEL, HI, ht)));
            drawSwitch(g, x + w - 4f - 24f, y + (h - 12f) / 2f, 24f, 12f, owner, value, ht);
            if (hitOwner instanceof Entry) addHit(x, y, w, h, vt, vb, H_SWITCH, (Entry) hitOwner, (Module) owner, null, 0, 0, 0);
        }
        return h;
    }

    private float drawSetting(GuiGraphics g, Module m, Setting s, float x, float y, float w, float vt, float vb, int mx, int my) {
        if (s instanceof Setting.Bool b) {
            float h = 26f;
            if (y + h >= vt && y <= vb) {
                float y0 = Math.max(y, vt), y1 = Math.min(y + h, vb);
                boolean hov = inside(mx, my, x, y0, w, y1 - y0);
                float ht = ease(s, 4, hov, 16f);
                if (ht > 0.01f) ShaderUi.fancy(g, Mode.PILL, x - 2f, y + 1f, w + 4f, h - 2f, 7f, c(a(HI, Math.round(0x0E * ht))));
                String lab = fit(SdfText.Face.SANS, b.name, 8.8f, w - 40f);
                txt(g, SdfText.Face.SANS, lab, x + 4f, y + h / 2f, 8.8f, c(mix(LABEL, HI, ht)));
                drawSwitch(g, x + w - 4f - 24f, y + (h - 12f) / 2f, 24f, 12f, s, b.value, ht);
                addHit(x, y, w, h, vt, vb, H_SET, null, m, s, 0, 0, 0);
            }
            return h;
        }
        if (s instanceof Setting.Num n) {
            if (n.labels != null && n.labels.length <= 6) return drawChips(g, m, n, x, y, w, vt, vb, mx, my);
            float h = 32f;
            if (y + h >= vt && y <= vb) {
                float y0 = Math.max(y, vt), y1 = Math.min(y + h, vb);
                boolean hov = inside(mx, my, x, y0, w, y1 - y0), drag = activeSlider == s;
                float ht = ease(s, 4, hov || drag, 16f);
                String v = n.display();
                txt(g, SdfText.Face.SANS, fit(SdfText.Face.SANS, n.name, 8.6f, w - twS(v, 8.4f) - 18f), x + 4f, y + 8f, 8.6f, c(mix(MUTED, TEXT, ht)));
                txt(g, SdfText.Face.SANS, v, x + w - 4f - twS(v, 8.4f), y + 8f, 8.4f, c(mix(acc, acc2, ht)));
                float frac = Mth.clamp(spring(s, 2, (float) Mth.clamp(n.fraction(), 0.0, 1.0), drag ? 40f : 22f, 1f), 0f, 1f);
                drawTrack(g, x + 6f, y + 18f, w - 12f, frac, ht, false, 0);
                addHit(x, y + 10f, w, 20f, vt, vb, H_SET, null, m, s, 0, x + 6f, w - 12f);
            }
            return h;
        }
        if (s instanceof Setting.Hue hu) {
            float h = 32f;
            if (y + h >= vt && y <= vb) {
                float y0 = Math.max(y, vt), y1 = Math.min(y + h, vb);
                boolean hov = inside(mx, my, x, y0, w, y1 - y0), drag = activeSlider == s;
                float ht = ease(s, 4, hov || drag, 16f);
                txt(g, SdfText.Face.SANS, hueLabel(hu), x + 4f, y + 8f, 8.6f, c(mix(MUTED, TEXT, ht)));
                ShaderUi.knob(g, x + w - 10f, y + 8f, 4.6f, c(hu.argb(255)));
                float frac = Mth.clamp(spring(s, 2, hu.hue, drag ? 40f : 22f, 1f), 0f, 1f);
                drawTrack(g, x + 6f, y + 18f, w - 12f, frac, ht, true, hu.argb(255));
                addHit(x, y + 10f, w, 20f, vt, vb, H_SET, null, m, s, 0, x + 6f, w - 12f);
            }
            return h;
        }
        if (s instanceof Setting.Button btn) {
            float h = 28f;
            if (y + h >= vt && y <= vb) {
                float y0 = Math.max(y, vt), y1 = Math.min(y + h, vb);
                boolean hov = inside(mx, my, x, y0, w, y1 - y0);
                float ht = ease(s, 4, hov, 16f);
                ShaderUi.fancy(g, Mode.GLASS, x, y + 2f, w, 22f, 8f, c(mix(cardFill, cardFillHi, ht)));
                ShaderUi.fancy(g, Mode.GLASS_EDGE, x, y + 2f, w, 22f, 8f, c(mix(a(edgeBase, 0x55), a(acc, 0xE0), ht)));
                String lab = fit(SdfText.Face.BOLD, btn.name, 8.8f, w - 16f);
                txt(g, SdfText.Face.BOLD, lab, x + (w - tw(SdfText.Face.BOLD, lab, 8.8f)) / 2f, y + 13f, 8.8f, c(mix(TEXT, HI, ht)));
                addHit(x, y + 2f, w, 22f, vt, vb, H_SET, null, m, s, 0, 0, 0);
            }
            return h;
        }
        if (s instanceof Setting.Text t) {
            float h = 28f;
            if (y + h >= vt && y <= vb) {
                boolean focus = typing == t;
                float ft = ease(s, 4, focus, 12f);
                ShaderUi.fancy(g, Mode.GLASS, x, y + 2f, w, 22f, 8f, c(mix(cardFill, cardFillHi, ft)));
                ShaderUi.fancy(g, Mode.GLASS_EDGE, x, y + 2f, w, 22f, 8f, c(mix(a(edgeBase, 0x55), a(acc, 0xE0), ft)));
                boolean blink = focus && (int) (time * 2f) % 2 == 0;
                if (t.value.isEmpty()) {
                    txt(g, SdfText.Face.SANS, fit(SdfText.Face.SANS, t.name, 8.6f, w - 24f), x + 9f, y + 13f, 8.6f, c(MUTED));
                    if (blink) txt(g, SdfText.Face.SANS, "_", x + 9f, y + 13f, 8.6f, c(TEXT));
                } else {
                    txt(g, SdfText.Face.SANS, tail(t.value, 8.6f, w - 24f) + (blink ? "_" : ""), x + 9f, y + 13f, 8.6f, c(TEXT));
                }
                addHit(x, y + 2f, w, 22f, vt, vb, H_SET, null, m, s, 0, 0, 0);
            }
            return h;
        }
        return 0f;
    }

    /** Slider track: dark groove, gradient fill, round knob with a halo that grows on hover / drag. Rainbow strip for colour settings. */
    private void drawTrack(GuiGraphics g, float x, float y, float w, float frac, float hot, boolean rainbow, int knobColor) {
        float th = 5f, cx = x + w * frac, cy = y + th / 2f;
        if (rainbow) {
            ShaderUi.shape(g, Mode.HUE, Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + th), 2, 0xFFFFFFFF);
        } else {
            ShaderUi.fancy(g, Mode.PILL, x, y, w, th, ShaderUi.PILL_R, c(trackBg));
            ShaderUi.fancy(g, Mode.PILL_RING, x, y, w, th, ShaderUi.PILL_R, c(a(edgeBase, 0x22)));
            float fw = Math.max(th, w * frac);
            ShaderUi.fancy(g, Mode.GRAD, x, y, fw, th, ShaderUi.PILL_R, c(acc));
        }
        float kr = 4.6f + 1.4f * hot;
        ShaderUi.spot(g, cx, cy, kr + 7f, kr + 7f, 14, c(a(rainbow ? knobColor : acc, Math.round(0x58 * hot))));
        ShaderUi.knob(g, cx, cy + 0.9f, kr + 0.4f, c(0x55000000));
        ShaderUi.knob(g, cx, cy, kr, c(KNOB));
        if (rainbow) ShaderUi.knob(g, cx, cy, kr - 2.2f, c(knobColor));
    }

    private float drawChips(GuiGraphics g, Module m, Setting.Num n, float x, float y, float w, float vt, float vb, int mx, int my) {
        // chips wrap onto further rows; the height depends on the labels, so lay them out even when off screen
        float cx = x + 4f, cy = y + 20f, rowH = 20f;
        int cur = Mth.clamp((int) Math.round(n.value - n.min), 0, n.labels.length - 1);
        boolean visible = y + 20f + rowH * 3f >= vt && y <= vb;
        if (visible) {
            txt(g, SdfText.Face.SANS, fit(SdfText.Face.SANS, n.name, 8.6f, w - 70f), x + 4f, y + 8f, 8.6f, c(MUTED));
            String v = n.labels[cur];
            txt(g, SdfText.Face.SANS, v, x + w - 4f - twS(v, 8.4f), y + 8f, 8.4f, c(acc));
        }
        for (int i = 0; i < n.labels.length; i++) {
            float cw = twS(n.labels[i], 8f) + 16f;
            if (cx + cw > x + w) { cx = x + 4f; cy += rowH; }
            if (visible && cy + 16f >= vt && cy <= vb) {
                boolean hov = inside(mx, my, cx, Math.max(cy, vt), cw, Math.min(cy + 16f, vb) - Math.max(cy, vt));
                float st = ease(n, 20 + i, i == cur, 14f), ht = ease(n, 40 + i, hov, 16f);
                ShaderUi.fancy(g, Mode.PILL, cx, cy, cw, 16f, ShaderUi.PILL_R, c(mix(mix(offTrack, mix(offTrack, HI, 0.10f), ht), mix(acc, acc2, 0.25f), st)));
                if (st < 0.98f) ShaderUi.fancy(g, Mode.PILL_RING, cx, cy, cw, 16f, ShaderUi.PILL_R, c(a(edgeBase, Math.round(0x34 * (1f - st)))));
                if (st > 0.02f) ShaderUi.spot(g, cx + cw / 2f, cy + 8f, cw * 0.8f, 14f, 10, c(a(acc, Math.round(0x34 * st))));
                txt(g, SdfText.Face.SANS, n.labels[i], cx + 8f, cy + 8f, 8f, c(mix(mix(MUTED, TEXT, ht), ONACC, st)));
                addHit(cx, cy, cw, 16f, vt, vb, H_CHIP, null, m, n, i, 0, 0);
            }
            cx += cw + 5f;
        }
        return (cy - y) + 16f + 8f;
    }

    private void drawTip(GuiGraphics g) {
        float tw = twS(hoverTip, 8f) + 14f, th = 16f;
        float x = Mth.clamp(tipX, 4f, vw() - tw - 4f), y = Mth.clamp(tipY, 4f, vh() - th - 4f);
        float saved = fade;
        fade = saved * ease("tip", 0, true, 20f);
        ShaderUi.shadow(g, x, y + 2f, tw, th, 8, c(0x70000000));
        ShaderUi.fancy(g, Mode.GLASS, x, y, tw, th, 7f, c(popFill));
        ShaderUi.fancy(g, Mode.GLASS_EDGE, x, y, tw, th, 7f, c(a(acc, 0xA0)));
        txt(g, SdfText.Face.SANS, hoverTip, x + 7f, y + th / 2f, 8f, c(TEXT));
        fade = saved;
    }

    // ---------------- input ----------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (closing || leaving) return true;
        if (warn != null && click.button() != 0) return true;   // right / middle clicks do nothing while the warning is up
        double mx = click.x() / uiScale, my = click.y() / uiScale;
        int button = click.button();
        typing = null;                          // clicking anywhere drops text focus; a text box re-focuses itself in handle()
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains(mx, my) && h.w() > 0f && h.h() > 0f) { handle(h, button, mx); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    private void handle(Hit h, int button, double mx) {
        switch (h.kind()) {
            case H_WARN_NO -> { if (button == 0) warn = null; }
            case H_WARN_YES -> {
                if (button != 0 || warn == null) return;
                Warn w = warn;
                warn = null;
                if (w.bind()) listening = w.module(); else w.module().toggle();
            }
            case H_CLEAR -> { query = ""; midScroll.reset(); }
            case H_CLOSE -> { if (button == 0) { detail = false; typing = null; listening = null; } }
            case H_RAIL -> {
                if (button != 0) return;
                Category[] cats = Category.values();
                go(h.idx() < cats.length ? cats[h.idx()] : null);
            }
            case H_CARD -> {
                if (button == 0 || button == 1) open(h.entry());      // left or right click: open the settings panel
                else if (button == 2 && h.entry().kind() == 0) requestBind(h.module());
            }
            case H_SWITCH -> { if (button == 0) requestToggle(h.module()); }
            case H_KEY, H_KEYROW -> {
                if (button == 0) requestBind(h.module());
                else if (button == 1) { h.module().key = -1; listening = null; }
            }
            case H_CHIP -> {
                if (button == 0 && h.setting() instanceof Setting.Num n) n.value = Mth.clamp(n.min + h.idx() * n.step, n.min, n.max);
            }
            case H_SET -> {
                if (button != 0) return;
                Setting s = h.setting();
                if (s instanceof Setting.Bool b) b.value = !b.value;
                else if (s instanceof Setting.Button btn) btn.action.run();
                else if (s instanceof Setting.Text t) typing = t;
                else if (s instanceof Setting.Num || s instanceof Setting.Hue) {
                    activeSlider = s; sliderX = h.ax(); sliderW = Math.max(1f, h.aw());
                    applySlider(mx);
                }
            }
            default -> {}
        }
    }

    private void applySlider(double mx) {
        double f = (mx - sliderX) / sliderW;
        if (activeSlider instanceof Setting.Num n) n.setFraction(f);
        else if (activeSlider instanceof Setting.Hue h) h.hue = (float) Mth.clamp(f, 0.0, 0.999);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (warn != null) return true;
        mx /= uiScale; my /= uiScale;
        Scroll s = null;
        if (inside((float) mx, (float) my, px + MAIN_X - 4f, py + LIST_TOP - 4f, MAIN_W + 8f, LIST_BOT - LIST_TOP + 8f)) s = detail ? paneScroll : midScroll;
        if (s != null) {
            scrollSound();
            s.target = Mth.clamp(s.target - (float) vAmount * 38f, 0f, s.max);
            s.lastMove = time;
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
        if (closing || leaving || warn != null) return true;
        int cp = input.codepoint();
        if (typing != null) {
            if (typing.value.length() < typing.maxLen && !Character.isISOControl(cp) && !Character.isWhitespace(cp)) typing.value += new String(Character.toChars(cp));
            return true;
        }
        if (listening != null) return false;
        String t = new String(Character.toChars(cp));
        if (t.isBlank() && query.isEmpty()) return true;
        if (query.length() < 32 && !Character.isISOControl(cp)) {
            query += t;
            detail = false;
            midScroll.reset();
            listS[0] = 0.45f; listS[1] = 0f;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.input();
        if (warn != null) {                    // the warning is modal: Escape = cancel, the menu key still closes the menu
            if (key == GLFW.GLFW_KEY_ESCAPE) warn = null;
            else if (key == dev.kuro.client.util.MenuKey.key()) { KuroClient.modules.consumeGuiKey(); closing = true; warn = null; }
            return true;
        }
        if (typing != null) {                   // a text box has focus: it gets every key (Right Shift included)
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
        if (listening == null && !query.isEmpty()) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) { query = query.substring(0, query.length() - 1); detail = false; midScroll.reset(); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (detail) detail = false;           // first Escape: back from the settings to the search results
                else { query = ""; midScroll.reset(); }
                return true;
            }
        }
        if (listening == null && detail && key == GLFW.GLFW_KEY_ESCAPE) { detail = false; return true; }   // Escape: back from a module's settings to the cards
        if (listening != null) {
            boolean clear = key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE;
            listening.key = clear ? -1 : key;
            listening = null;
            return true;
        }
        if (key == dev.kuro.client.util.MenuKey.key()) { KuroClient.modules.consumeGuiKey(); closing = true; return true; }
        return super.keyPressed(input);
    }
}
