package dev.kuro.client.module.impl;

import dev.kuro.client.util.SdfText;
import dev.kuro.client.KuroClient;
import dev.kuro.client.gui.Theme;
import dev.kuro.client.gui.UiThemes;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.HudPanel;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.Projector;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Colour and look of the whole client. Color/Saturation recolour every menu AND every in-world overlay (Sus Chunk
 * Finder, ESP tags, ...); Corner radius, Menu opacity and Menu blur style the windows. Switching the module on also
 * shows an on-screen watermark and enabled-module list. Saturation 0 = the default white look.
 */
public class Hud extends Module {
    public static final String NAME = "Kuro";
    public static final String VERSION = "1.0";
    private static Hud instance;

    /** The theme preset every menu follows (Glass, Sidebar, Blob, Classic windows, Radial, HUD, overlays, trails). Classic uses the Color / Saturation sliders below. */
    private final Setting.Num uiTheme = add(new Setting.Num("UI theme", 0, 0, UiThemes.count() - 1, 1).labelled(UiThemes.names()));
    private final Setting.Button prevTheme = add(new Setting.Button("Previous theme", () -> stepTheme(-1)));
    private final Setting.Button nextTheme = add(new Setting.Button("Next theme", () -> stepTheme(1)));

    // Classic theme: one colour for everything: the menus, every window and the in-world overlays (Sus Chunk Finder, ESP tags, ...)
    private final Setting.Hue color = add(new Setting.Hue("Color", 0.6f));
    private final Setting.Num saturation = add(new Setting.Num("Saturation", 0.0, 0.0, 1.0, 0.05));
    private final Setting.Num menuOpacity = add(new Setting.Num("Menu opacity %", 92, 60, 100, 2));
    private final Setting.Num overlayOpacity = add(new Setting.Num("Overlay opacity %", 82, 30, 100, 2));
    private final Setting.Num corners = add(new Setting.Num("Corner radius", 4, 0, 8, 1));
    private final Setting.Bool blur = add(new Setting.Bool("Menu blur", true));
    /** Shader look: glow halo around windows (0 = off) and the organic blob edge instead of a plain rounded rectangle. */
    private final Setting.Num glow = add(new Setting.Num("Glow %", 60, 0, 100, 5));
    private final Setting.Bool blobShape = add(new Setting.Bool("Blob shape", true));
    private final Setting.Bool accentOnly = add(new Setting.Bool("One accent colour", false));
    private final Setting.Bool plates = add(new Setting.Bool("Glass plates", true));   // shader plates behind the watermark and module list
    private final Setting.Bool watermark = add(new Setting.Bool("Title pill", true));            // top centre: name | fps | ping | speed
    private final Setting.Bool keybinds = add(new Setting.Bool("Keybinds panel", true));          // top left: every bound module as [key] [name]
    private final Setting.Bool armorPanel = add(new Setting.Bool("Armor panel", true));           // under the keybinds: durability % per piece
    private final Setting.Bool targetHud = add(new Setting.Bool("Target HUD", true));             // above the hotbar: face, name, health, armour
    private final Setting.Bool list = add(new Setting.Bool("Module list", true));                 // right edge: compact pills
    /** Switches view bobbing + sprint/speed FOV change off while ESP-style overlays are on, so boxes never slide off their block. */
    private final Setting.Bool steady = add(new Setting.Bool("Steady view (ESP)", true));

    private final Setting.Button editLayout = add(new Setting.Button("Edit HUD layout...", () -> mc.setScreen(new dev.kuro.client.gui.HudEditScreen(mc.screen))));

    public Hud() {
        super("HUD", Category.RENDER);
        instance = this;
        applyTheme();
    }

    /** Picked menu colour (0..1 hue) and tint strength, used by the sidebar menu for its accent. */
    public static float hue() {
        if (instance == null) return 0.6f;
        UiThemes.UiTheme t = UiThemes.get(instance.uiTheme.intValue());
        return t.custom ? instance.color.hue : t.hue;
    }
    public static float saturation() {
        if (instance == null) return 0f;
        UiThemes.UiTheme t = UiThemes.get(instance.uiTheme.intValue());
        return t.custom ? (float) instance.saturation.value : t.sat;
    }

    /** Index of the chosen UI theme (see gui/UiThemes). 0 = Classic when the HUD module does not exist yet. */
    public static int themeIndex() { return instance == null ? 0 : instance.uiTheme.intValue(); }

    /** Picks a theme by index (used by the next / previous buttons, the .theme command and the menus). */
    public static void setTheme(int index) {
        if (instance == null) return;
        instance.uiTheme.value = Mth.clamp(index, 0, UiThemes.count() - 1);
        applyTheme();
    }

    private static void stepTheme(int d) {
        if (instance == null) return;
        int n = UiThemes.count();
        setTheme(((instance.uiTheme.intValue() + d) % n + n) % n);
    }

    public static boolean steadyView() { return instance == null || instance.steady.value; }

    /** Palette button in the menu: next accent colour (turns the tint on if the menu was still white / grey). */
    public static void cycleAccent() {
        if (instance == null) return;
        instance.uiTheme.value = 0;                      // the palette button edits the Classic colour
        if (instance.saturation.value < 0.2) instance.saturation.value = 0.6;
        instance.color.hue = (instance.color.hue + 0.1f) % 1f;
        applyTheme();
    }

    /** Sets the colour directly (used by the glass menu's colour dock when "Sync HUD colour" is on). */
    public static void setAccent(float hue, float sat) {
        if (instance == null) return;
        instance.uiTheme.value = 0;
        instance.color.hue = Mth.clamp(hue, 0f, 0.999f);
        instance.saturation.value = Mth.clamp(Math.round(sat * 20f) / 20.0, 0.0, 1.0);
        applyTheme();
    }

    /** Palette button, right click: back to the plain white / grey look. */
    public static void resetAccent() {
        if (instance == null) return;
        instance.uiTheme.value = 0;
        instance.saturation.value = 0.0;
        applyTheme();
    }

    /** True when the menus should blur the world behind them (uses the game's own menu blur, see Options > Accessibility). */
    public static boolean menuBlur() { return instance == null || instance.blur.value; }

    /** Pushes the chosen colour, corner radius and opacity into the menu theme and the overlays. Cheap; called whenever a menu screen draws. */
    public static void applyTheme() {
        if (instance == null) return;
        UiThemes.UiTheme th = UiThemes.get(instance.uiTheme.intValue());
        float hue = hue(), sat = saturation();
        if (th != lastTheme) {                           // a new preset: also flip the classic windows to its light / dark choice
            if (!th.custom) Theme.dark = !th.light;
            lastTheme = th;
        }
        dev.kuro.client.util.Ui.radius = instance.corners.intValue();
        dev.kuro.client.util.ShaderUi.glow = (float) instance.glow.value / 100f;
        dev.kuro.client.util.ShaderUi.blob = instance.blobShape.value;
        Theme.apply(hue, sat, (float) instance.menuOpacity.value / 100f);
        // the in-world overlays follow the same colour; saturation is also how strongly their plates are tinted
        dev.kuro.client.util.Look.apply(hue, sat, (float) instance.overlayOpacity.value / 100f, instance.accentOnly.value);
        if (!th.custom) dev.kuro.client.util.Look.ACCENT = 0xFF000000 | th.accent;
    }

    private static UiThemes.UiTheme lastTheme;

    private int rgb() {
        UiThemes.UiTheme t = UiThemes.get(uiTheme.intValue());
        if (!t.custom) return 0xFF000000 | t.accent;
        return 0xFF000000 | Mth.hsvToRgb(color.hue, (float) saturation.value, 1f);
    }

    // ------------------------------------------------------------------ HUD layout (like the reference screenshot)

    private static final EquipmentSlot[] ARMOR = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    private LivingEntity lastTarget;
    private long lastTargetMs;
    private float shownHp = -1f;
    private int shownTargetId = -1;

    // every plate below can be dragged in the HUD layout editor; until it is, each one sits at its default spot
    private final HudPanel pTitle = new HudPanel(this, "Title pill");
    private final HudPanel pKeys = new HudPanel(this, "Keybinds");
    private final HudPanel pArmor = new HudPanel(this, "Armor panel");
    private final HudPanel pTarget = new HudPanel(this, "Target HUD");
    private final HudPanel pList = new HudPanel(this, "Module list");

    @Override
    public List<HudElement> hudElements() {
        List<HudElement> l = new ArrayList<>();
        if (watermark.value) l.add(pTitle);
        if (keybinds.value) l.add(pKeys);
        if (armorPanel.value) l.add(pArmor);
        if (targetHud.value) l.add(pTarget);
        if (list.value) l.add(pList);
        return l;
    }

    @Override
    public void onHud(GuiGraphics g) {
        applyTheme();
        int c = rgb();
        int r = Math.min(dev.kuro.client.util.Ui.radius, 5);
        if (watermark.value) drawTitle(g, c, r);
        int keysH = 0;
        if (keybinds.value) keysH = drawKeybinds(g, c);
        if (armorPanel.value) drawArmor(g, c, keybinds.value ? 4 + keysH + 4 : 4);
        if (targetHud.value) drawTarget(g, c);
        if (list.value) drawList(g, c, r);
    }

    /** Top centre: name | fps | ping | speed in blocks per second. */
    private void drawTitle(GuiGraphics g, int c, int r) {
        List<String> parts = new ArrayList<>();
        parts.add(NAME);
        parts.add(mc.getFps() + "fps");
        if (mc.player != null && mc.getConnection() != null) {
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null && info.getLatency() > 0) parts.add(info.getLatency() + "ms");
        }
        if (mc.player != null) {
            Vec3 mv = mc.player.getDeltaMovement();
            parts.add(String.format(Locale.ROOT, "%.1fbl", Math.hypot(mv.x, mv.z) * 20.0));
        }
        int gap = 9, total = 0;
        for (String p : parts) total += SdfText.w(p);
        total += gap * (parts.size() - 1);
        int w = total + 16;
        pTitle.place((Projector.width() - w) / 2, 3, w, 15);
        int x1 = pTitle.x(), y1 = pTitle.y();
        dev.kuro.client.util.Look.plate(g, x1, y1, x1 + w, y1 + 15);
        int x = x1 + 8;
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            SdfText.ui(g, p, x, y1 + 4, i == 0 ? c : dev.kuro.client.util.Look.TEXT, false);
            x += SdfText.w(p);
            if (i < parts.size() - 1) {
                g.fill(x + gap / 2, y1 + 4, x + gap / 2 + 1, y1 + 11, dev.kuro.client.util.Look.withAlpha(dev.kuro.client.util.Look.MUTED, 0x90));
                x += gap;
            }
        }
    }

    /** Top left: "Keybinds" header, then one row per bound module: [key] [name] (name lit when the module is on). Returns the height the armor panel stacks under. */
    private int drawKeybinds(GuiGraphics g, int c) {
        List<Module> bound = new ArrayList<>();
        for (Module m : KuroClient.modules.sorted()) if (m != this && m.key > 0) bound.add(m);
        if (bound.size() > 12) bound = bound.subList(0, 12);
        int hw = SdfText.w("Keybinds") + 26;
        int w = hw;
        for (Module m : bound) w = Math.max(w, SdfText.w(keyName(m.key)) + 8 + 2 + SdfText.w(m.name) + 8);
        int stackH = 17 + bound.size() * 14;
        pKeys.place(4, 4, w, bound.isEmpty() ? 14 : stackH - 2);
        int x = pKeys.x(), y = pKeys.y();
        dev.kuro.client.util.Look.plate(g, x, y, x + hw, y + 14);
        SdfText.ui(g, "Keybinds", x + 6, y + 3, dev.kuro.client.util.Look.TEXT, false);
        for (int i = 0; i < 3; i++) g.fill(x + hw - 14 + i * 4, y + 6, x + hw - 12 + i * 4, y + 8, c);   // three dots
        y += 17;
        for (Module m : bound) {
            String k = keyName(m.key);
            int kw = SdfText.w(k) + 8, nw = SdfText.w(m.name) + 8;
            dev.kuro.client.util.Look.plate(g, x, y, x + kw, y + 12);
            SdfText.ui(g, k, x + 4, y + 2, dev.kuro.client.util.Look.MUTED, false);
            dev.kuro.client.util.Look.plate(g, x + kw + 2, y, x + kw + 2 + nw, y + 12);
            SdfText.ui(g, m.name, x + kw + 6, y + 2, m.isEnabled() ? c : dev.kuro.client.util.Look.MUTED, false);
            y += 14;
        }
        return stackH;
    }

    /** "Armor" header, then helmet / chest / legs / boots with their durability percent. Sits under the keybinds until dragged. */
    private void drawArmor(GuiGraphics g, int c, int autoY) {
        if (mc.player == null) return;
        int hw = SdfText.w("Armor") + 26;
        pArmor.place(4, autoY, Math.max(hw, 58), 17 + ARMOR.length * 17 + 3);
        int x = pArmor.x(), y = pArmor.y();
        dev.kuro.client.util.Look.plate(g, x, y, x + hw, y + 14);
        SdfText.ui(g, "Armor", x + 6, y + 3, dev.kuro.client.util.Look.TEXT, false);
        // little shield glyph on the right of the header
        int sx = x + hw - 12;
        g.fill(sx, y + 4, sx + 7, y + 8, c);
        g.fill(sx + 1, y + 8, sx + 6, y + 10, c);
        g.fill(sx + 2, y + 10, sx + 5, y + 11, c);
        y += 17;
        int w = 58;
        dev.kuro.client.util.Look.plate(g, x, y, x + w, y + ARMOR.length * 17 + 3);
        int iy = y + 3;
        for (EquipmentSlot s : ARMOR) {
            ItemStack st = mc.player.getItemBySlot(s);
            if (!st.isEmpty()) g.renderItem(st, x + 4, iy - 1);
            String t = "-";
            int col = dev.kuro.client.util.Look.MUTED;
            if (!st.isEmpty() && st.isDamageableItem() && st.getMaxDamage() > 0) {
                float frac = 1f - (float) st.getDamageValue() / st.getMaxDamage();
                t = Math.round(frac * 100f) + "%";
                col = dev.kuro.client.util.Look.health(frac);
            } else if (!st.isEmpty()) {
                t = "100%";
                col = dev.kuro.client.util.Look.GOOD;
            }
            SdfText.ui(g, t, x + 24, iy + 3, col, false);
            iy += 17;
        }
    }

    /** Above the hotbar: face, name, health number, a health bar and a thin armour bar for the crosshair target (or your last target for a moment). */
    private void drawTarget(GuiGraphics g, int c) {
        if (mc.player == null || mc.level == null) return;
        LivingEntity t = null;
        Entity cross = mc.crosshairPickEntity;
        if (cross instanceof LivingEntity le && le.isAlive() && cross != mc.player) t = le;
        if (t == null) {
            LivingEntity aura = AttackAura.currentTarget();
            if (aura != null && aura.isAlive()) t = aura;
        }
        long now = System.currentTimeMillis();
        if (t != null) { lastTarget = t; lastTargetMs = now; }
        else if (lastTarget != null && lastTarget.isAlive() && !lastTarget.isRemoved() && now - lastTargetMs < 2500
                && mc.player.distanceTo(lastTarget) < 16) t = lastTarget;
        if (t == null && !dev.kuro.client.gui.HudEditScreen.editing()) return;

        String name;
        float max, hp, shown, armor;
        if (t != null) {
            max = Math.max(1f, t.getMaxHealth());
            hp = Mth.clamp(t.getHealth(), 0f, max);
            if (t.getId() != shownTargetId || shownHp < 0f) { shownTargetId = t.getId(); shownHp = hp; }
            shownHp += (hp - shownHp) * 0.2f;                       // bar eases to the real value
            if (Math.abs(shownHp - hp) < 0.02f) shownHp = hp;
            shown = shownHp;
            armor = (float) Mth.clamp(t.getArmorValue() / 20.0, 0.0, 1.0);
            name = t.getName().getString();
        } else {                                                    // layout editor: a stand-in so the plate can be found and moved
            name = "Target"; max = 20f; hp = 20f; shown = 20f; armor = 0.5f;
        }

        String hs = String.format(Locale.ROOT, "%.1f", hp);
        int w = Math.max(96, 22 + SdfText.w(name) + 10 + SdfText.w(hs) + 8);
        int h = 26;
        pTarget.place((Projector.width() - w) / 2, Projector.height() - 58 - h, w, h);
        int x1 = pTarget.x(), y1 = pTarget.y(), y2 = y1 + h;
        dev.kuro.client.util.Look.plate(g, x1, y1, x1 + w, y2);

        // face
        if (t instanceof AbstractClientPlayer p) PlayerFaceRenderer.draw(g, p.getSkin(), x1 + 4, y1 + 4, 18);
        else g.fill(x1 + 4, y1 + 4, x1 + 22, y1 + 22, dev.kuro.client.util.Look.withAlpha(c, 0x60));

        int tx = x1 + 27;
        SdfText.ui(g, name, tx, y1 + 3, dev.kuro.client.util.Look.TEXT, false);
        SdfText.ui(g, hs, x1 + w - 6 - SdfText.w(hs), y1 + 3, dev.kuro.client.util.Look.health(hp / max), false);
        int bw = w - 27 - 6;
        dev.kuro.client.util.Look.bar(g, tx, y1 + 14, bw, 3, shown / max, dev.kuro.client.util.Look.GOOD);
        dev.kuro.client.util.Look.bar(g, tx, y1 + 19, bw, 2, armor, dev.kuro.client.util.Look.WARN);
    }

    /** Right edge: enabled modules as small pills, widest first, clear of the status-effect icons. */
    private void drawList(GuiGraphics g, int c, int r) {
        List<String> names = new ArrayList<>();
        for (Module m : KuroClient.modules.modules) if (m.isEnabled() && m != this) names.add(m.name);
        if (names.isEmpty() && dev.kuro.client.gui.HudEditScreen.editing()) { names.add("Module list"); names.add("Fly"); }   // stand-ins for the layout editor
        if (names.isEmpty()) return;
        names.sort(Comparator.comparingInt((String n) -> SdfText.w(n)).reversed());
        int widest = 0;
        for (String n : names) widest = Math.max(widest, SdfText.w(n));
        int bw = widest + (plates.value ? 8 : 4), step = plates.value ? 13 : 10;
        int autoY = 4;
        if (mc.player != null && !mc.player.getActiveEffects().isEmpty()) autoY += 28;   // vanilla effect icons live in the top-right corner
        pList.place(Projector.width() - 2 - bw, autoY, bw, names.size() * step - (plates.value ? 2 : 0));
        int x2 = pList.x() + bw, y = pList.y();
        for (String n : names) {
            int w = SdfText.w(n);
            if (plates.value) {
                dev.kuro.client.util.Ui.panel(g, x2 - w - 8, y, x2, y + 11, Math.min(r, 4), dev.kuro.client.util.Look.BG, dev.kuro.client.util.Look.EDGE, 0, false);
            }
            SdfText.ui(g, n, x2 - w - (plates.value ? 4 : 2), y + (plates.value ? 2 : 0), dev.kuro.client.util.Look.withAlpha(c, 0xE6), !plates.value);
            y += step;
        }
    }

    /** "Grave", "R", "F4"... for a GLFW key code. */
    private static String keyName(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_GRAVE_ACCENT: return "Grave";
            case GLFW.GLFW_KEY_SPACE: return "Space";
            case GLFW.GLFW_KEY_TAB: return "Tab";
            case GLFW.GLFW_KEY_LEFT_SHIFT: return "LShift";
            case GLFW.GLFW_KEY_RIGHT_SHIFT: return "RShift";
            case GLFW.GLFW_KEY_LEFT_CONTROL: return "LCtrl";
            case GLFW.GLFW_KEY_RIGHT_CONTROL: return "RCtrl";
            case GLFW.GLFW_KEY_LEFT_ALT: return "LAlt";
            case GLFW.GLFW_KEY_RIGHT_ALT: return "RAlt";
            case GLFW.GLFW_KEY_ENTER: return "Enter";
            case GLFW.GLFW_KEY_BACKSPACE: return "Back";
            case GLFW.GLFW_KEY_UP: return "Up";
            case GLFW.GLFW_KEY_DOWN: return "Down";
            case GLFW.GLFW_KEY_LEFT: return "Left";
            case GLFW.GLFW_KEY_RIGHT: return "Right";
            default: break;
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        String n = GLFW.glfwGetKeyName(key, 0);
        return n != null ? n.toUpperCase(Locale.ROOT) : "K" + key;
    }
}
