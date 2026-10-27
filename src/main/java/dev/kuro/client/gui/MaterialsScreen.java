package dev.kuro.client.gui;

import dev.kuro.client.module.impl.AutoBuild;
import dev.kuro.client.util.SchematicInfo;
import dev.kuro.client.util.SdfText;
import dev.kuro.client.util.Ui;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything the chosen schematic needs, with how many you carry, and a tick box per item to check it off yourself.
 * Ticks are remembered per schematic. Click a row to tick it. Mouse wheel scrolls, Esc closes.
 */
public class MaterialsScreen extends Screen {
    private static final int ROW_H = 20, W = 440;
    private static final Path SAVE = FabricLoader.getInstance().getConfigDir().resolve("kuro-autobuild-checked.txt");

    private final Screen parent;
    private final AutoBuild module;
    private final Set<String> checked = new HashSet<>();     // "<file>|<item id>"
    private boolean byName, hideChecked;
    private int scroll, top, left, listBottom, visibleRows;
    private final List<Row> rows = new ArrayList<>();
    private final int[] btnX = new int[4], btnW = new int[4];
    private static final String[] BTN = {"Sort", "Hide ticked", "Tick all", "Clear"};

    private record Row(Item item, String id, String name, int needed, int have) {}

    public MaterialsScreen(Screen parent, AutoBuild module) {
        super(Component.literal("Materials"));
        this.parent = parent;
        this.module = module;
        load();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (dev.kuro.client.module.impl.Hud.menuBlur()) super.renderBackground(g, mx, my, delta);
        else g.fill(0, 0, width, height, 0x90000000);
    }

    private String key(String id) { return module.selectedFile() + "|" + id; }

    private void rebuild(SchematicInfo si) {
        rows.clear();
        for (Map.Entry<Item, Integer> e : si.items.entrySet()) {
            Item it = e.getKey();
            String id = BuiltInRegistries.ITEM.getKey(it).toString();
            String name = new ItemStack(it).getHoverName().getString();
            int have = 0;
            if (minecraft != null && minecraft.player != null)
                for (int i = 0; i < 41; i++) {
                    ItemStack st = minecraft.player.getInventory().getItem(i);
                    if (st.getItem() == it) have += st.getCount();
                }
            if (hideChecked && checked.contains(key(id))) continue;
            rows.add(new Row(it, id, name, e.getValue(), have));
        }
        if (byName) rows.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));      // otherwise the file's order: most needed first
    }

    private static String amount(int n, int stack) {
        if (stack <= 1 || n < stack) return String.format(Locale.ROOT, "%,d", n);
        return String.format(Locale.ROOT, "%,d", n) + "  (" + (n / stack) + " st" + (n % stack == 0 ? "" : " + " + n % stack) + ")";
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        dev.kuro.client.module.impl.Hud.applyTheme();
        SchematicInfo si = module.materialsInfo();
        left = (width - W) / 2;
        top = 48;
        listBottom = height - 24;

        Ui.panel(g, left, 8, left + W, 26, Math.max(Ui.radius, 6), Theme.TITLE, Theme.PANEL_EDGE, Theme.GLOW, false);
        SdfText.ui(g, "Materials", left + 6, 14, Theme.TITLE_TEXT, false);

        String[] labels = {"Sort: " + (byName ? "name" : "most"), "Hide ticked: " + (hideChecked ? "on" : "off"), BTN[2], BTN[3]};
        int x = left + W - 4;
        for (int i = 3; i >= 0; i--) {
            btnW[i] = SdfText.w(labels[i]) + 10;
            x -= btnW[i];
            btnX[i] = x;
            button(g, x, 10, btnW[i], 12, labels[i], mx, my);
            x -= 4;
        }

        if (module.selectedFile() == null) { message(g, "Choose a schematic first (Auto Build settings, or .build gui)."); return; }
        if (si == null) { message(g, "Reading " + module.selectedFile() + " ..."); return; }
        if (si.items.isEmpty()) { message(g, "No materials could be read from this file."); return; }

        rebuild(si);
        int ticked = 0;
        for (Item it : si.items.keySet()) if (checked.contains(key(BuiltInRegistries.ITEM.getKey(it).toString()))) ticked++;
        SdfText.ui(g, SdfText.trim(module.selectedFile() + "   |   " + si.totalBlocks + " blocks, " + si.items.size() + " kinds   |   "
                + ticked + " / " + si.items.size() + " ticked", W - 4), left + 2, 33, Theme.TEXT, false);

        int visible = Math.max(1, (listBottom - top) / ROW_H);
        visibleRows = visible;
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - visible));
        for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
            Row r = rows.get(i + scroll);
            int y = top + i * ROW_H;
            boolean hover = mx >= left && mx < left + W && my >= y && my < y + ROW_H - 1;
            boolean on = checked.contains(key(r.id()));
            Ui.corners(g, left, y, left + W, y + ROW_H - 1, 4, hover ? Theme.ROW_HOVER : Theme.ROW, true, true, true, true);
            // tick box
            Ui.round(g, left + 5, y + 4, left + 16, y + 15, on ? Theme.ON : Theme.TITLE);
            if (on) SdfText.ui(g, "x", left + 8, y + 6, Theme.ON_TEXT, false);
            g.renderItem(new ItemStack(r.item()), left + 22, y + 1);
            int text = on ? 0xFF7F8A80 : Theme.TEXT;
            SdfText.ui(g, SdfText.trim(r.name(), 150), left + 44, y + 6, text, false);
            int stack = new ItemStack(r.item()).getMaxStackSize();
            SdfText.ui(g, "need " + amount(r.needed(), stack), left + 200, y + 6, text, false);
            boolean enough = r.have() >= r.needed();
            SdfText.ui(g, "have " + String.format(Locale.ROOT, "%,d", r.have()), left + W - 70, y + 6, enough ? 0xFF7FFFA0 : text, false);
        }
        if (rows.isEmpty()) message(g, "Everything is ticked off.");
    }

    private void message(GuiGraphics g, String text) {
        Ui.round(g, left, top, left + W, top + ROW_H, Theme.ROW);
        SdfText.ui(g, text, left + 6, top + 6, Theme.TEXT, false);
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
            for (int i = 0; i < 4; i++) {
                if (mx < btnX[i] || mx >= btnX[i] + btnW[i]) continue;
                switch (i) {
                    case 0 -> byName = !byName;
                    case 1 -> hideChecked = !hideChecked;
                    case 2 -> setAll(true);
                    default -> setAll(false);
                }
                scroll = 0;
                return true;
            }
        }
        if (my >= top && mx >= left && mx < left + W) {
            int row = ((int) my - top) / ROW_H + scroll;
            if (row >= scroll && row < scroll + visibleRows && row < rows.size()) {
                String k = key(rows.get(row).id());
                if (!checked.remove(k)) checked.add(k);
                save();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void setAll(boolean on) {
        SchematicInfo si = module.materialsInfo();
        if (si == null) return;
        for (Item it : si.items.keySet()) {
            String k = key(BuiltInRegistries.ITEM.getKey(it).toString());
            if (on) checked.add(k); else checked.remove(k);
        }
        save();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll -= (int) Math.signum(v) * 3;
        if (scroll < 0) scroll = 0;
        return true;
    }

    private void save() {
        try { Files.write(SAVE, checked); } catch (IOException ignored) {}
    }

    private void load() {
        try { if (Files.exists(SAVE)) checked.addAll(Files.readAllLines(SAVE)); } catch (IOException ignored) {}
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
}
