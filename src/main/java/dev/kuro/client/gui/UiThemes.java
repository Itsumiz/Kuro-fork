package dev.kuro.client.gui;

import dev.kuro.client.module.impl.Hud;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Theme presets. ONE preset (HUD > "UI theme") drives every menu: the Glass menu reads the full glass palette and
 * background decoration from it, the Sidebar / Blob / Classic windows / Radial menu / HUD / in-world overlays get their
 * accent, hue and light-or-dark choice from it (through Hud.applyTheme -> Theme.apply / Look.apply), and Player Trails
 * can take its colours from it too.
 *
 * "Classic" is the original look: it keeps using the HUD Color / Saturation sliders. Every other preset ignores them.
 * To add a theme, add one line in the static block below (dark(...) and light(...) derive the whole palette from a hue).
 */
public final class UiThemes {
    private UiThemes() {}

    /** What is drawn on the Glass panel behind the controls. */
    public enum Decor { FLOWER, HEARTS, FLAG, SNOW, PETALS, EMBERS, BUBBLES, STARS, GRID }

    public static final class UiTheme {
        public final String id, name;
        /** Classic only: colours come from the HUD sliders instead of this preset. */
        public boolean custom;
        public boolean light;
        /** Hue / tint for the hue-driven screens (Sidebar, Blob, Classic windows, HUD, overlays). */
        public float hue, sat;
        /** 0xRRGGBB accent colours (accent2 is the gradient partner). */
        public int accent, accent2;
        /** Glass palette, ARGB for pop, 0xRRGGBB for the rest. */
        public int text, muted, faint, hi, label, body, onAcc, knobOff, knob, pop;
        public int panel, rail, card, cardHi, cardSel, head, edge, offTrack, trackBg;
        /** Glass fill alphas (before the menu opacity): panel, rail, card, cardHi, cardSel, head. */
        public int[] alpha = {0xCC, 0x60, 0x78, 0xB4, 0xD0, 0xB8};
        /** Player Trails colours (0xRRGGBB): head and tail. */
        public int trailA, trailB;
        public Decor decor = Decor.FLOWER;

        UiTheme(String id, String name) { this.id = id; this.name = name; }
    }

    private static final List<UiTheme> ALL = new ArrayList<>();

    private static int hsv(float h, float s, float v) {
        return Mth.hsvToRgb(h - (float) Math.floor(h), Mth.clamp(s, 0f, 1f), Mth.clamp(v, 0f, 1f)) & 0xFFFFFF;
    }

    private static int gray(float v) { int b = Math.round(Mth.clamp(v, 0f, 1f) * 255f); return (b << 16) | (b << 8) | b; }

    /** Dark smoked glass tinted with a hue. tint 0..1 = how coloured the glass is. */
    private static UiTheme dark(String id, String name, float hue, float tint, int accent, int accent2, Decor decor, int trailA, int trailB) {
        UiTheme t = new UiTheme(id, name);
        t.hue = hue; t.sat = tint;
        tint = Mth.clamp(tint * 1.6f, 0f, 1f);   // glass fills are very dark, so they need a stronger tint than the windows to read as coloured
        t.accent = accent; t.accent2 = accent2; t.decor = decor; t.trailA = trailA; t.trailB = trailB;
        t.text = hsv(hue, 0.09f * tint, 1f);
        t.muted = hsv(hue, 0.25f * tint, 0.72f);
        t.faint = hsv(hue, 0.32f * tint, 0.48f);
        t.hi = lift(accent, 0.45f);
        t.label = hsv(hue, 0.15f * tint, 0.93f);
        t.body = hsv(hue, 0.20f * tint, 0.86f);
        t.onAcc = hsv(hue, 0.60f * tint, 0.16f);
        t.knobOff = hsv(hue, 0.20f * tint, 0.78f);
        t.knob = 0xFFFFFF;
        t.pop = 0xF2000000 | hsv(hue, 0.45f * tint, 0.09f);
        t.panel = hsv(hue, 0.40f * tint, 0.080f);
        t.rail = hsv(hue, 0.45f * tint, 0.115f);
        t.card = hsv(hue, 0.46f * tint, 0.165f);
        t.cardHi = hsv(hue, 0.48f * tint, 0.228f);
        t.cardSel = hsv(hue, 0.52f * tint, 0.340f);
        t.head = hsv(hue, 0.50f * tint, 0.140f);
        t.edge = lift(accent, 0.12f);
        t.offTrack = hsv(hue, 0.35f * tint, 0.290f);
        t.trackBg = hsv(hue, 0.45f * tint, 0.110f);
        t.alpha = new int[] {0xDD, 0x80, 0x8C, 0xB8, 0xD8, 0xC0};
        return t;
    }

    /** Bright frosted glass tinted with a hue (dark text, accent edges). */
    private static UiTheme light(String id, String name, float hue, float tint, int accent, int accent2, Decor decor, int trailA, int trailB) {
        UiTheme t = new UiTheme(id, name);
        t.light = true;
        t.hue = hue; t.sat = tint;
        t.accent = accent; t.accent2 = accent2; t.decor = decor; t.trailA = trailA; t.trailB = trailB;
        t.text = hsv(hue, 0.92f * tint, 0.34f);
        t.muted = hsv(hue, 0.41f * tint, 0.60f);
        t.faint = hsv(hue, 0.28f * tint, 0.76f);
        t.hi = accent;
        t.label = hsv(hue, 0.89f * tint, 0.42f);
        t.body = hsv(hue, 0.65f * tint, 0.48f);
        t.onAcc = 0xFFFFFF;
        t.knobOff = hsv(hue, 0.42f * tint, 0.84f);
        t.knob = accent;
        t.pop = 0xF8FFFFFF;
        t.panel = 0xFFFFFF;
        t.rail = hsv(hue, 0.04f * tint, 1f);
        t.card = 0xFFFFFF;
        t.cardHi = hsv(hue, 0.11f * tint, 1f);
        t.cardSel = hsv(hue, 0.18f * tint, 1f);
        t.head = hsv(hue, 0.07f * tint, 1f);
        t.edge = accent;
        t.offTrack = hsv(hue, 0.18f * tint, 0.96f);
        t.trackBg = hsv(hue, 0.13f * tint, 0.97f);
        t.alpha = new int[] {0xF4, 0xEA, 0xF2, 0xF6, 0xFA, 0xEE};
        return t;
    }

    private static int lift(int rgb, float f) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        r += Math.round((255 - r) * f); g += Math.round((255 - g) * f); b += Math.round((255 - b) * f);
        return (r << 16) | (g << 8) | b;
    }

    static {
        // 0: Classic = the original black and white glass; follows the HUD Color / Saturation sliders everywhere else
        UiTheme c = new UiTheme("classic", "Classic");
        c.custom = true;
        c.hue = 0.6f; c.sat = 0f;
        c.accent = 0xF4F4F4; c.accent2 = 0xFFFFFF;
        c.text = 0xF0F0F0; c.muted = 0x8C8C8C; c.faint = 0x595959; c.hi = 0xFFFFFF; c.label = 0xCFCFCF; c.body = 0xC4C4C4;
        c.onAcc = 0x0C0C0C; c.knobOff = 0xB8B8B8; c.knob = 0xFFFFFF; c.pop = 0xF2101010;
        c.panel = gray(0.055f); c.rail = gray(0.030f); c.card = gray(0.100f); c.cardHi = gray(0.150f); c.cardSel = gray(0.220f);
        c.head = gray(0.090f); c.edge = 0xFFFFFF; c.offTrack = gray(0.140f); c.trackBg = gray(0.070f);
        c.trailA = 0xFFFFFF; c.trailB = 0x9AA3B5;
        ALL.add(c);

        ALL.add(dark("midnight", "Midnight", 0.62f, 0.55f, 0x6C8CFF, 0x9DB4FF, Decor.STARS, 0x5B7CFF, 0x9B6BFF));
        ALL.add(dark("ocean", "Ocean", 0.52f, 0.50f, 0x35D0E8, 0x7BE8F5, Decor.BUBBLES, 0x2CD5E6, 0x3B82F6));
        ALL.add(dark("emerald", "Emerald", 0.40f, 0.50f, 0x3DDC97, 0x8DF0C0, Decor.FLOWER, 0x34D399, 0xA3E635));
        ALL.add(dark("sunset", "Sunset", 0.04f, 0.55f, 0xFF7A45, 0xFFB36B, Decor.FLOWER, 0xFF8A3D, 0xFF3D8B));
        ALL.add(dark("ember", "Ember", 0.00f, 0.60f, 0xFF4D4D, 0xFF9A5C, Decor.EMBERS, 0xFF3B1F, 0xFFC020));
        ALL.add(dark("amethyst", "Amethyst", 0.76f, 0.60f, 0xB07CFF, 0xD3B3FF, Decor.STARS, 0xA855F7, 0xEC4899));
        ALL.add(dark("sakura", "Sakura", 0.93f, 0.50f, 0xFF8FB8, 0xFFC2D8, Decor.PETALS, 0xFF8FB8, 0xFFD6E6));

        // the old Astolfo theme, values kept exactly
        UiTheme a = dark("astolfo", "Astolfo", 0.924f, 0.60f, 0xFF5FA8, 0xFF9AD0, Decor.HEARTS, 0xFF5FA8, 0xFFB3D9);
        a.text = 0xFFE9F4; a.muted = 0xB98AA6; a.faint = 0x7A5568; a.hi = 0xFFB3D9; a.label = 0xF3CCE0; a.body = 0xE3B8CD;
        a.onAcc = 0x2A0F20; a.knobOff = 0xC9A0B7; a.knob = 0xFFFFFF; a.pop = 0xF2180D16;
        a.panel = 0x140C14; a.rail = 0x1E1019; a.card = 0x2A1624; a.cardHi = 0x3A1D30; a.cardSel = 0x57284A; a.head = 0x24121E;
        a.edge = 0xFF6FB0; a.offTrack = 0x4A3342; a.trackBg = 0x1C0F19;
        ALL.add(a);

        ALL.add(dark("gold", "Gold", 0.12f, 0.45f, 0xF5C451, 0xFFE29A, Decor.FLOWER, 0xF5C451, 0xFF8A3D));
        ALL.add(dark("frost", "Frost", 0.56f, 0.30f, 0x9FD8FF, 0xDDF1FF, Decor.SNOW, 0x9FD8FF, 0xFFFFFF));
        ALL.add(dark("cyber", "Cyber", 0.50f, 0.55f, 0x00F0FF, 0xFF2BD6, Decor.GRID, 0x00F0FF, 0xFF2BD6));

        // the old Israel theme (the flag as the panel), values kept exactly
        UiTheme i = light("israel", "Israel", 0.62f, 1f, 0x0038B8, 0x1F5FE0, Decor.FLAG, 0x1F5FE0, 0x7FB0FF);
        i.text = 0x071B57; i.muted = 0x5A6A99; i.faint = 0x8C99C2; i.hi = 0x0038B8; i.label = 0x12296B; i.body = 0x2B3F7A;
        i.onAcc = 0xFFFFFF; i.knobOff = 0x7C93D6; i.knob = 0x0038B8; i.pop = 0xF8FFFFFF;
        i.panel = 0xFFFFFF; i.rail = 0xF4F7FF; i.card = 0xFFFFFF; i.cardHi = 0xE4ECFF; i.cardSel = 0xD2E1FF; i.head = 0xEEF3FF;
        i.edge = 0x0038B8; i.offTrack = 0xC9D6F5; i.trackBg = 0xD6E0F8;
        i.alpha = new int[] {0xFF, 0xEA, 0xF2, 0xF6, 0xFA, 0xEE};
        ALL.add(i);

        ALL.add(light("daylight", "Daylight", 0.60f, 0.75f, 0x3D6BFF, 0x7CA0FF, Decor.FLOWER, 0x3D6BFF, 0x7CC4FF));

        // White Sakura: frosted white glass with soft cherry-blossom pink accents and drifting petals
        ALL.add(light("whitesakura", "White Sakura", 0.95f, 0.55f, 0xE8709F, 0xF7B6CF, Decor.PETALS, 0xFFFFFF, 0xFFB7D0));
    }

    // ------------------------------------------------------------------ lookup

    public static int count() { return ALL.size(); }
    public static UiTheme get(int i) { return ALL.get(Mth.clamp(i, 0, ALL.size() - 1)); }

    public static String[] names() {
        String[] n = new String[ALL.size()];
        for (int k = 0; k < n.length; k++) n[k] = ALL.get(k).name;
        return n;
    }

    /** Index of a theme by id or name (case-insensitive, spaces ignored), -1 if there is none. */
    public static int find(String s) {
        if (s == null) return -1;
        String q = s.toLowerCase(Locale.ROOT).replace(" ", "");
        for (int k = 0; k < ALL.size(); k++)
            if (ALL.get(k).id.equals(q) || ALL.get(k).name.toLowerCase(Locale.ROOT).replace(" ", "").equals(q)) return k;
        return -1;
    }

    /** The theme chosen in HUD > UI theme. */
    public static UiTheme current() { return get(Hud.themeIndex()); }

    // ------------------------------------------------------------------ colours the other screens ask for

    /** Accent (opaque ARGB) for the current theme. Classic returns the hue-based accent the HUD sliders give. */
    public static int accent() {
        UiTheme t = current();
        if (t.custom) {
            float s = Math.max(0.45f, Hud.saturation());
            return 0xFF000000 | hsv(Hud.hue(), s, 0.95f);
        }
        return 0xFF000000 | t.accent;
    }

    /** Gradient partner of accent(). */
    public static int accent2() {
        UiTheme t = current();
        if (t.custom) {
            float s = Math.max(0.45f, Hud.saturation());
            return 0xFF000000 | hsv(Hud.hue() + 0.09f, Math.min(1f, s * 0.9f), 1f);
        }
        return 0xFF000000 | t.accent2;
    }

    private static final int[] NAVY = {0x070E1C, 0x050A16, 0x101A2E, 0x162340, 0x0A1222, 0xEDF2FA, 0x7C8AA3, 0x56627A, 0x1F2A44, 0x2A3858};

    /**
     * Palette of the always-dark menus (Sidebar, Radial): 0xRRGGBB for {background, rail, row, row hover, settings box, text,
     * muted text, faint text, switch off, switch off hover}. Classic is the original navy; other themes tint it with their hue.
     */
    public static int[] navy() {
        UiTheme t = current();
        if (t.custom) return NAVY;
        float h = t.hue, k = Mth.clamp(t.sat * 1.4f, 0f, 1f);
        return new int[] {
                hsv(h, 0.75f * k, 0.110f), hsv(h, 0.77f * k, 0.086f), hsv(h, 0.65f * k, 0.180f), hsv(h, 0.65f * k, 0.250f), hsv(h, 0.70f * k, 0.130f),
                hsv(h, 0.06f * k, 0.980f), hsv(h, 0.24f * k, 0.640f), hsv(h, 0.30f * k, 0.480f), hsv(h, 0.54f * k, 0.270f), hsv(h, 0.52f * k, 0.350f)};
    }

    /** Trail head colour (0xRRGGBB): the theme's, or for Classic the HUD colour. */
    public static int trailA() {
        UiTheme t = current();
        return t.custom ? hsv(Hud.hue(), Math.max(0.35f, Math.min(0.85f, Hud.saturation() + 0.35f)), 1f) : t.trailA;
    }

    /** Trail tail colour (0xRRGGBB). */
    public static int trailB() {
        UiTheme t = current();
        return t.custom ? hsv(Hud.hue() + 0.12f, Math.max(0.25f, Math.min(0.7f, Hud.saturation() + 0.2f)), 1f) : t.trailB;
    }
}
