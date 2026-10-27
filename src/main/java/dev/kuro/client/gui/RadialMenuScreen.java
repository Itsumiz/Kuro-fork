package dev.kuro.client.gui;

import dev.kuro.client.module.impl.RadialMenu;
import dev.kuro.client.module.impl.GlassUi;
import dev.kuro.client.module.impl.SidebarUi;
import dev.kuro.client.module.impl.Hud;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.ShaderUi;
import dev.kuro.client.util.Ui;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The Radial Menu module's screen: a ring split into three slices (top, lower right, lower left) that pops open while you
 * HOLD the module's key and closes the moment you let go. Left click a slice = send its text / command, right click = type
 * its text (Enter saves, Esc cancels). Esc also closes. Each slice is ONE shader quad (util/ShaderUi.slice, ui_slice*.fsh).
 *
 * Look: just the three slices and their texts. An empty slice shows a "+", the full text of the slice under the cursor
 * appears in the middle, and one quiet hint line sits underneath. No captions, logos or per-slice hints.
 */
public class RadialMenuScreen extends Screen {
    private static final double[] ANGLE = {Math.toRadians(-90), Math.toRadians(30), Math.toRadians(150)};   // slice centres

    private final RadialMenu module;
    private int editing;                 // slice being typed into, -1 = none
    private String buffer = "";
    private float openT;
    private final float[] hover = new float[3];
    private long lastNs;
    private float dt = 0.016f;
    private int cx, cy, outer;
    private boolean closing;             // fading out; the screen closes when the fade is done
    private boolean heldAtOpen;          // opened by holding the module's key: letting go closes the menu (and sends the slice under the cursor)
    private boolean clicked;             // a click already sent something: letting go of the key must not send it again
    private int lastHover = -1;          // slice under the cursor in the last frame

    // wrapped label per slice, recomputed only when the text changes (wrapping measures text many times)
    private final String[] wrapSrc = {null, null, null};
    private final String[][] wrapOut = new String[3][];
    private int wrapWidth = -1;

    public RadialMenuScreen(RadialMenu module, int edit) {
        super(Component.literal("Radial Menu"));
        this.module = module;
        edit(edit);
        if (edit >= 0) buffer = module.slot(edit);
        heldAtOpen = edit < 0 && keyDown();
    }

    private boolean keyDown() {
        var win = net.minecraft.client.Minecraft.getInstance().getWindow();
        return module.key > 0 && win != null && GLFW.glfwGetKey(win.handle(), module.key) == GLFW.GLFW_PRESS;
    }

    /** Starts / stops typing into a slice. Typing keeps the menu open even if the key is let go. */
    private void edit(int slot) {
        editing = slot;
        if (slot >= 0) heldAtOpen = false;
    }

    /** Fades the menu out instead of cutting it (Esc, a click that sends, or the menu key being let go). */
    @Override
    public void onClose() { closing = true; }

    @Override
    public void tick() {
        if (closing) { if (openT <= 0f) minecraft.setScreen(null); return; }
        if (heldAtOpen && editing < 0 && !keyDown()) {            // the key was let go
            int s = lastHover;
            if (module.releaseSelects() && !clicked && s >= 0 && !module.slot(s).isEmpty()) module.send(s);
            closing = true;
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void removed() { module.screenRemoved(); }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (Hud.menuBlur()) super.renderBackground(g, mx, my, delta);
    }

    private static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** Scales the colour's own alpha by fa / 255. */
    private static int fade(int argb, int fa) { return alpha(argb, Math.round((argb >>> 24) * fa / 255f)); }

    private static int alpha(int argb, int a) { return (Mth.clamp(a, 0, 255) << 24) | (argb & 0xFFFFFF); }

    private int tw(String s) { return (int) Math.ceil(SdfText.width(SdfText.Face.SANS, s, SdfText.BASE)); }

    private String fit(String s, int max) {
        if (tw(s) <= max) return s;
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n).stripTrailing() + "...";
            if (tw(t) <= max) return t;
        }
        return "...";
    }

    /** At most two lines: the first breaks at a space where it can, the second is shortened with "..." if it still does not fit. */
    private String[] wrap(String s, int max) {
        if (tw(s) <= max) return new String[]{s};
        int cut = -1;
        for (int i = s.length() - 1; i > 0; i--)
            if (s.charAt(i) == ' ' && tw(s.substring(0, i)) <= max) { cut = i; break; }
        if (cut < 0) {                                        // one long word: break inside it
            for (int n = s.length() - 1; n > 0; n--) if (tw(s.substring(0, n)) <= max) { cut = n; break; }
            if (cut < 0) cut = 1;
        }
        return new String[]{s.substring(0, cut).stripTrailing(), fit(s.substring(cut).stripLeading(), max)};
    }

    private String[] label(int i, String text, int max) {
        if (max != wrapWidth) { wrapWidth = max; for (int k = 0; k < 3; k++) wrapSrc[k] = null; }
        if (!text.equals(wrapSrc[i])) { wrapSrc[i] = text; wrapOut[i] = wrap(text, max); }
        return wrapOut[i];
    }

    private String centreSrc;
    private int centreW = -1;
    private String[] centreOut;

    /** Same wrapping for the text in the middle, cached on its own. */
    private String[] centreLines(String text, int max) {
        if (max != centreW || !text.equals(centreSrc)) { centreW = max; centreSrc = text; centreOut = wrap(text, max); }
        return centreOut;
    }

    private void text(GuiGraphics g, String s, float x, float y, float sc, int color, boolean bold) {
        SdfText.draw(g, bold ? SdfText.Face.BOLD : SdfText.Face.SANS, s, x, y, SdfText.BASE * sc, color);
    }

    /** Draws text centred on x at scale sc. */
    private void centered(GuiGraphics g, String s, float x, float y, float sc, int color, boolean bold) {
        SdfText.centered(g, bold ? SdfText.Face.BOLD : SdfText.Face.SANS, s, x, y, SdfText.BASE * sc, color);
    }

    /** Which slice is under (mx, my): -1 over the centre hole or outside the ring. */
    private int sliceAt(double mx, double my) {
        double dx = mx - cx, dy = my - cy, d = Math.sqrt(dx * dx + dy * dy);
        if (d < outer * 0.34 || d > outer + 16) return -1;
        int best = 0;
        double bd = -2;
        for (int i = 0; i < 3; i++) {
            double dot = (dx * Math.cos(ANGLE[i]) + dy * Math.sin(ANGLE[i])) / Math.max(d, 1e-6);
            if (dot > bd) { bd = dot; best = i; }
        }
        return best;
    }

    // palette for this frame (set in render): follows the menu's light / dark switch, or the sidebar menu's navy look
    private int cText, cMuted, cFaint, cDim, cEdgeA, cEdgeB, cOnA, cOnB, cOnText;
    private int cFillTop, cFillBottom, cEmptyTop, cEmptyBottom, cBox;

    private void palette() {
        Hud.applyTheme();                                    // make sure Theme matches the HUD colour / light-dark choice
        if (SidebarUi.active() || GlassUi.active()) {        // the sidebar and glass menus are always dark navy
            int acc = UiThemes.accent();
            int acc2 = UiThemes.accent2();
            int[] nv = UiThemes.navy();
            cText = 0xFF000000 | nv[5]; cMuted = 0xFF000000 | nv[6]; cFaint = 0xFF000000 | nv[7]; cDim = 0x40000000;
            cEdgeA = acc; cEdgeB = acc2; cOnA = alpha(acc, 0xE0); cOnB = alpha(acc2, 0xCC); cOnText = 0xFFFFFFFF;
            cFillTop = 0xE5000000 | nv[3]; cFillBottom = 0xE5000000 | nv[0]; cEmptyTop = 0xB0000000 | nv[2]; cEmptyBottom = 0xB0000000 | nv[1];
            cBox = 0xE0000000 | nv[4];
            return;
        }
        // classic menu: same colours as the windows, so dark mode is dark and light mode is light
        cText = Theme.TEXT;
        cMuted = mix(Theme.TEXT, Theme.MUTED, 0.55f);
        cFaint = mix(Theme.TEXT, Theme.MUTED, 0.8f);
        cDim = Theme.dark ? 0x40000000 : 0x40FFFFFF;
        cEdgeA = Theme.BORDER; cEdgeB = Theme.BORDER;
        cOnA = alpha(Theme.ON, 0xF0); cOnB = alpha(Theme.ON, 0xD8); cOnText = Theme.ON_TEXT;
        cFillTop = Theme.ROW; cFillBottom = Theme.ROW_HOVER;
        cEmptyTop = alpha(Theme.ROW, (Theme.ROW >>> 24) * 6 / 10); cEmptyBottom = alpha(Theme.ROW_HOVER, (Theme.ROW_HOVER >>> 24) * 6 / 10);
        cBox = Theme.ROW;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        long now = System.nanoTime();
        dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        openT = closing ? Math.max(0f, openT - dt * 9f) : Math.min(1f, openT + dt * 7f);   // quick both ways: it follows the key
        float e = openT * openT * (3f - 2f * openT);

        palette();
        boolean sidebar = SidebarUi.active() || GlassUi.active();

        cx = width / 2;
        cy = height / 2 - 8;
        outer = Mth.clamp(Math.min(width, height) / 2 - 44, 44, 84);
        g.fill(0, 0, width, height, alpha(cDim, Math.round((cDim >>> 24) * e)));

        float rOut = outer * (0.7f + 0.3f * e);
        float rIn = outer * 0.4f * e;                        // the hole opens up from nothing: disc -> ring
        int hov = editing >= 0 ? editing : sliceAt(mx, my);
        if (!closing) lastHover = hov;
        else hov = lastHover;                                // the slice you let go on stays lit while it fades
        int fa = Math.round(255 * Mth.clamp(e * 1.6f, 0f, 1f));   // everything fades with the menu

        for (int i = 0; i < 3; i++) {
            hover[i] += ((i == hov ? 1f : 0f) - hover[i]) * (1f - (float) Math.exp(-dt * 16f));
            float h = hover[i];
            float off = 4f * h * e;                          // the hovered slice steps out a little
            boolean filled = !module.slot(i).isEmpty();
            int baseTop = filled ? cFillTop : cEmptyTop, baseBottom = filled ? cFillBottom : cEmptyBottom;
            int top = mix(baseTop, cOnA, h), bottom = mix(baseBottom, cOnB, h * 0.8f);
            // quiet edge until hovered, then it lights up
            int edgeTop = sidebar ? alpha(cEdgeA, Math.round(0x30 + 0xCF * h)) : mix(alpha(cEdgeA, 0x70), alpha(Theme.ON, 0xFF), h);
            int edgeBottom = sidebar ? alpha(cEdgeB, Math.round(0x28 + 0xB7 * h)) : mix(alpha(cEdgeB, 0x60), alpha(Theme.ON, 0xFF), h);
            float sx = cx + (float) Math.cos(ANGLE[i]) * off, sy = cy + (float) Math.sin(ANGLE[i]) * off;
            ShaderUi.slice(g, false, i, sx, sy, rOut, rIn, fade(mix(top, bottom, 0.5f), fa));          // body: frosted gradient + rim light (shader)
            ShaderUi.slice(g, true, i, sx, sy, rOut, rIn, fade(mix(edgeTop, edgeBottom, 0.5f), fa));   // edge + soft glow (shader)
        }

        // slice contents: the text, or a "+" when the slice is empty
        float la = Mth.clamp((e - 0.5f) / 0.5f, 0f, 1f);
        if (la > 0.05f) {
            int a8 = Math.max(8, Math.round(255 * la));
            float rMid = (rIn + rOut) / 2f;
            int labelW = Math.max(40, (int) (rOut * 0.78f));
            for (int i = 0; i < 3; i++) {
                float h = hover[i];
                float off = 4f * h * e;
                float lx = cx + (float) Math.cos(ANGLE[i]) * (rMid + off), ly = cy + (float) Math.sin(ANGLE[i]) * (rMid + off);
                String t = i == editing ? buffer : module.slot(i);
                if (t.isEmpty()) {
                    int col = alpha(mix(cFaint, cOnText, h), a8);
                    int px = Math.round(lx), py = Math.round(ly);
                    g.fill(px - 5, py - 1, px + 5, py + 1, col);
                    g.fill(px - 1, py - 5, px + 1, py + 5, col);
                } else {
                    int col = alpha(mix(cText, cOnText, h), a8);
                    String[] lines = label(i, t, labelW);
                    if (lines.length == 1) {
                        centered(g, lines[0], lx, ly - 4, 0.95f, col, true);
                    } else {
                        centered(g, lines[0], lx, ly - 10, 0.95f, col, true);
                        centered(g, lines[1], lx, ly + 2, 0.95f, col, true);
                    }
                }
            }
            // centre: the full text of the slice under the cursor (nothing when the cursor is in the middle)
            if (editing >= 0 || hov >= 0) {
                int s = editing >= 0 ? editing : hov;
                boolean plain = editing >= 0 || module.slot(s).isEmpty();
                String c = editing >= 0 ? "Slot " + (editing + 1) : plain ? "Add text" : module.slot(s);
                int max = Math.max(24, (int) ((rIn * 2 - 10) / 0.75f));
                String[] lines = plain ? new String[]{c} : centreLines(c, max);
                int col = alpha(cMuted, a8);
                if (lines.length == 1) centered(g, lines[0], cx, cy - 3, 0.75f, col, false);
                else {
                    centered(g, lines[0], cx, cy - 8, 0.75f, col, false);
                    centered(g, lines[1], cx, cy + 3, 0.75f, col, false);
                }
            }
        }

        // text box while typing a slice, one quiet hint line otherwise
        int by = cy + outer + 24;
        if (editing >= 0) {
            int bw = Math.min(300, width - 40), bx = cx - bw / 2;
            Ui.panel(g, bx, by, bx + bw, by + 16, 6, cBox, alpha(sidebar ? cEdgeA : Theme.ON, 0xCC), 0, false);   // shader text box
            String shown = buffer;
            while (!shown.isEmpty() && tw(shown) > bw - 16) shown = shown.substring(1);
            boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
            text(g, shown.isEmpty() && !blink ? "Type text or /command" : shown + (blink ? "_" : ""), bx + 6, by + 4, 1f,
                    shown.isEmpty() ? cMuted : cText, false);
            centered(g, "Enter = save   Esc = cancel   start with / for a command", cx, by + 22, 0.7f, cMuted, false);
        } else if (la > 0.5f) {
            String hint = heldAtOpen && !clicked && module.releaseSelects() ? "Let go to send   -   Right click to edit" : "Click to send   -   Right click to edit";
            centered(g, hint, cx, by, 0.7f, alpha(cFaint, Math.round(255 * la)), false);
        }
    }

    // ---------------- input ----------------

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (closing || editing >= 0) return true;            // fading out / finish typing first
        int i = sliceAt(click.x(), click.y());
        if (i < 0) return super.mouseClicked(click, doubled);
        if (click.button() == 0) {
            if (module.slot(i).isEmpty()) { edit(i); buffer = ""; return true; }   // empty slice: left click starts typing too
            module.send(i);
            clicked = true;
            if (module.closeAfterSending()) onClose();
        } else if (click.button() == 1) {
            edit(i);
            buffer = module.slot(i);
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (editing < 0) return false;
        int cp = input.codepoint();
        if (!Character.isISOControl(cp) && cp != 0xA7 && buffer.length() < 256) buffer += new String(Character.toChars(cp));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.input();
        if (closing) return true;
        if (editing >= 0) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { module.setSlot(editing, buffer); edit(-1); }
            else if (key == GLFW.GLFW_KEY_ESCAPE) edit(-1);
            else if (key == GLFW.GLFW_KEY_BACKSPACE) { if (!buffer.isEmpty()) buffer = buffer.substring(0, buffer.length() - 1); }
            else if (key == GLFW.GLFW_KEY_V && minecraft != null && minecraft.getWindow() != null
                    && (GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(minecraft.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)) {
                String clip = GLFW.glfwGetClipboardString(minecraft.getWindow().handle());   // Ctrl+V
                if (clip != null) for (char ch : clip.toCharArray())
                    if (!Character.isISOControl(ch) && ch != '§' && buffer.length() < 256) buffer += ch;
            }
            return true;
        }
        // no "module key closes it" here any more: the key is HELD to keep the menu open (key repeat would close it at once);
        // letting go is handled by ModuleManager (hold-to-use), Esc is handled by the Screen itself.
        return super.keyPressed(input);
    }
}
