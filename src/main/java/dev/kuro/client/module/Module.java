package dev.kuro.client.module;

import dev.kuro.client.event.EventBus;
import dev.kuro.client.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    protected static final Minecraft mc = Minecraft.getInstance();

    public final String name;
    public final Category category;
    public final List<Setting> settings = new ArrayList<>();
    public int key = -1;          // GLFW key code, -1 = unbound
    public boolean expanded;      // settings dropdown open in the GUI
    private boolean enabled;

    protected Module(String name, Category category) {
        this.name = name;
        this.category = category;
    }

    /** Saved in the config like a setting, but not shown in the menus (positions of draggable HUD panels). */
    public final List<Setting> saved = new ArrayList<>();

    protected <T extends Setting> T add(T setting) {
        settings.add(setting);
        return setting;
    }

    /** Registers a setting that is stored in the config but kept out of the module's settings list. */
    public <T extends Setting> T addSaved(T setting) {
        saved.add(setting);
        return setting;
    }

    /** Every setting that goes into the config file: the visible ones plus the hidden ones. */
    public List<Setting> persisted() {
        if (saved.isEmpty()) return settings;
        List<Setting> all = new ArrayList<>(settings);
        all.addAll(saved);
        return all;
    }

    /** The draggable HUD plates this module draws right now (the layout editor lists them). Modules that are a single plate implement HudElement. */
    public List<HudElement> hudElements() {
        return this instanceof HudElement e ? List.of(e) : List.of();
    }

    public boolean isEnabled() { return enabled; }
    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean value) {
        if (value == enabled) return;
        enabled = value;
        if (value) {
            EventBus.INSTANCE.register(this);
            onEnable();
        } else {
            onDisable();
            EventBus.INSTANCE.unregister(this);
        }
        dev.kuro.client.module.impl.Notifications.toggled(this, value);
    }

    protected void onEnable() {}
    protected void onDisable() {}
    
    /** Start of every client tick (before the player ticks). */
    public void onPreTick() {}
    /** End of every client tick. */
    public void onTick() {}
    /** After every module's onTick has run (end of the client tick). */
    public void onPostTick() {}
    /** Right before the client builds this tick's movement packet (vanilla pre-motion: the slot where input actions go out). */
    public void onPreMotion() {}
    /** Right after this tick's movement packet was handed to the connection (post-motion). */
    public void onPostMotion() {}
    /** True = the bound key works as "hold to use" (on while held, off when released) instead of a toggle. */
    public boolean holdToUse() { return false; }
    /** True = this module's onPreTick runs before everyone else's. */
    public boolean early() { return false; }
    /** Every frame, drawn as part of the HUD layer. */
    public void onHud(GuiGraphics g) {}
    
    /**
     * Every frame while the WORLD is being rendered. Queue ESP boxes here with WorldGfx.box(...) using real world
     * coordinates; they are drawn on the GPU through the game's own matrices (never drift, one draw call).
     */
    public void onWorld() {}
}