package dev.kuro.client.module;

import dev.kuro.client.gui.BlobGuiScreen;
import dev.kuro.client.gui.ClickGuiScreen;
import dev.kuro.client.gui.GlassGuiScreen;
import dev.kuro.client.gui.ModuleGui;
import dev.kuro.client.gui.SidebarGuiScreen;
import dev.kuro.client.module.impl.*;
import dev.kuro.client.util.Gfx;
import dev.kuro.client.util.WorldGfx;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import dev.kuro.client.util.Projector;
import dev.kuro.client.util.ViewLock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleManager {
    private final Minecraft mc = Minecraft.getInstance();
    public final List<Module> modules = new ArrayList<>();
    private final Map<Module, Boolean> wasDown = new HashMap<>();
    private boolean guiKeyWasDown;

    public ModuleManager() {
        modules.add(new BlockEsp());
        modules.add(new Hitbox());
        modules.add(new Tracers());
        modules.add(new BowAim());
        modules.add(new PearlFinder());
        modules.add(new PlayerIndicator());
        modules.add(new JumpBubbles());
        modules.add(new DamageNumbers());
        modules.add(new PlayerTrails());
        modules.add(new ReachCircle());
        modules.add(new TriggerBot());
        modules.add(new AntiBot());
        modules.add(new HoverTotem());
        modules.add(new AutoClicker());
        modules.add(new KillAura());
        modules.add(new SilentAura());
        modules.add(new BreachSwap());
        modules.add(new AutoMace());
        modules.add(new CrystalAura());
        modules.add(new SimpleCrystalAura());
        modules.add(new AttackAura());
        modules.add(new Backtrack());
        modules.add(new ProjectileAimBot());
        modules.add(new AutoAnchor());
        modules.add(new AnchorBomb());
        modules.add(new AnchorAura());
        modules.add(new SafeAnchor());
        modules.add(new CrystalDtap());
        modules.add(new AutoWeapon());
        modules.add(new AutoBox());
        modules.add(new AutoCobweb());
        modules.add(new AimAssist());
        modules.add(new Surround());
        modules.add(new TotemPops());
        modules.add(new HitSounds());
        modules.add(new Hud());
        modules.add(new SidebarUi());
        modules.add(new BlobUi());
        modules.add(new GlassUi());
        modules.add(new Fullbright());
        modules.add(new KnifeModule());
        modules.add(new UsernameHider());
        modules.add(new RadialMenu());
        modules.add(new PlayerInfo());
        modules.add(new Nametags());
        modules.add(new SpotifyViewer());
        modules.add(new Clock());
        modules.add(new FakeLoading());
        modules.add(new Performance());
        modules.add(new Stopwatch());
        modules.add(new ArmorHud());
        modules.add(new PotionHud());
        modules.add(new Keystrokes());
        modules.add(new CoordShare());
        modules.add(new ChatTimestamps());
        modules.add(new ChatMentions());
        modules.add(new Notifications());
        modules.add(new Freecam());
        modules.add(new DingDongBuilder());
        modules.add(new ToggleAll());
        modules.add(new Flight());
        modules.add(new Freeze());
        modules.add(new ElytraFly());
        modules.add(new ElytraHop());
        modules.add(new NoFall());
        modules.add(new AutoMLG());
        modules.add(new PearlCatch());
        modules.add(new Sprint());
        modules.add(new KeepSprint());
        modules.add(new WTap());
        modules.add(new Bhop());
        modules.add(new Scaffold());
        modules.add(new AutoWalk());
        modules.add(new EntitySpeed());
        modules.add(new BoatFly());
        modules.add(new AutoTunnel());
        modules.add(new AutoMiner());
        modules.add(new AutoEat());
        modules.add(new AutoTool());
        modules.add(new AutoFish());
        modules.add(new NoSlow());
        modules.add(new StashFinder());
        modules.add(new SusChunkFinder());
        modules.add(new NewChunks());
        modules.add(new LogoutSpots());
        modules.add(new Reach());
        modules.add(new HitboxExpand());
        modules.add(new AutoTotem());
        modules.add(new SafeWalk());
        modules.add(new Freelook());
        modules.add(new Zoom());
        modules.add(new ItemEsp());
        modules.add(new ChestStealer());
        modules.add(new InvSorter());
        modules.add(new AutoReplenish());
        modules.add(new AutoArmor());
        modules.add(new Criticals());
        modules.add(new Velocity());
        modules.add(new JumpReset());
        modules.add(new AutoShieldBreak());
        modules.add(new AutoDtap());
        modules.add(new FastCrystal());
        modules.add(new BaritoneControl());
        modules.add(new AutoBuild());
        modules.add(new ElytraSwap());
        modules.add(new Macro());
        modules.add(new MacroRecord());
        modules.add(new Waypoints());
        modules.add(new Snowflakes());
        modules.add(new WaypointAdd());
        modules.add(new GeodeEsp());
        modules.add(new AncientDebris());
        modules.add(new LightDebug());
        modules.add(new StorageEsp());
        modules.add(new ProjectileTrails());
        modules.add(new BreakProgress());
        modules.add(new WeatherControl());
        modules.add(new FoundList());
        modules.add(new TrailTracker());
        modules.add(new SkyModule());
        modules.add(new SpearSwap());
        modules.add(new FriendsModule());
        modules.add(new AttackPackets());
        modules.add(new FakePlayer());
        modules.add(new NoJumpDelay());
        modules.add(new FastPlace());
        modules.add(new NoBreakDelay());
        modules.add(new Step());
        modules.add(new NoWeb());
        modules.add(new MenuModule());
        modules.add(new ConfigModule());
        for (Module m : modules) if (m instanceof GlassUi || m instanceof Notifications) m.setEnabled(true);   // the glass menu is the default; the "Classic UI" button (Render > Glass UI) turns it off
    }

    /** The menu screen for the current style: Glass UI, else Blob UI, else Sidebar UI, else the classic windows. */
    public static Screen newMenu() {
        return GlassUi.active() ? new GlassGuiScreen()
                : BlobUi.active() ? new BlobGuiScreen()
                : SidebarUi.active() ? new SidebarGuiScreen()
                : new ClickGuiScreen();
    }

    /** The modules of one category, A-Z by name (the master list keeps its registration order, which is the tick order). */
    public List<Module> inCategory(Category c) {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) if (m.category == c) out.add(m);
        out.sort(BY_NAME);
        return out;
    }

    private static final java.util.Comparator<Module> BY_NAME = (a, b) -> a.name.compareToIgnoreCase(b.name);

    /** Every module, A-Z by name. For menus; never use it for ticking, the registration order matters there. */
    public List<Module> sorted() {
        List<Module> out = new ArrayList<>(modules);
        out.sort(BY_NAME);
        return out;
    }

    public void onPreTick() {
        dev.kuro.client.echo.handlers.SwapStateManager.tickEarly();
        for (Module m : modules) if (m.isEnabled() && m.early()) m.onPreTick();
        for (Module m : modules) if (m.isEnabled() && !m.early()) m.onPreTick();
    }

    public void onPreMotion() {
        for (Module m : modules) if (m.isEnabled()) m.onPreMotion();
    }

    public void onPostMotion() {
        for (Module m : modules) if (m.isEnabled()) m.onPostMotion();
    }

    public void onTick() {
        pollKeys();
        // Sidebar UI switched on/off while a menu is open: swap to the matching menu straight away
        if (mc.screen instanceof ModuleGui cur) {
            boolean glass = GlassUi.active(), blob = !glass && BlobUi.active(), side = !glass && !blob && SidebarUi.active();
            if (glass && !(cur instanceof GlassGuiScreen)) mc.setScreen(new GlassGuiScreen());
            else if (blob && !(cur instanceof BlobGuiScreen)) mc.setScreen(new BlobGuiScreen());
            else if (side && !(cur instanceof SidebarGuiScreen)) mc.setScreen(new SidebarGuiScreen());
            else if (!glass && !blob && !side && !(cur instanceof ClickGuiScreen)) mc.setScreen(new ClickGuiScreen());
        }
        for (Module m : modules) if (m.isEnabled()) m.onTick();
        for (Module m : modules) if (m.isEnabled()) m.onPostTick();
        dev.kuro.client.echo.handlers.SwapStateManager.tickPost();
    }

    /** Modules that project things from the world onto the screen. */
    private boolean anyOverlay() {
        for (Module m : modules)
            if (m.isEnabled() && (m instanceof BlockEsp || m instanceof SusChunkFinder || m instanceof StashFinder
                    || m instanceof NewChunks || m instanceof LogoutSpots || m instanceof Waypoints
                    || m instanceof Tracers || m instanceof BowAim || m instanceof PearlFinder || m instanceof PlayerIndicator || m instanceof PlayerInfo || m instanceof Nametags || m instanceof AncientDebris || m instanceof LightDebug || m instanceof StorageEsp || m instanceof ProjectileTrails || m instanceof BreakProgress || m instanceof GeodeEsp || m instanceof ItemEsp)) return true;
        return false;
    }

    /** Fired from the world render pass (WorldRenderEvents.BEFORE_TRANSLUCENT): queue and draw all world-space boxes. */
    public void onWorld(WorldRenderContext ctx) {
        if (mc.level == null || mc.player == null) return;
        if (!Projector.begin(mc)) return;
        WorldGfx.begin(mc, ctx.matrices(), Projector.camPos);
        try {
            for (Module m : modules) if (m.isEnabled()) m.onWorld();
        } finally {
            WorldGfx.end(mc);
        }
    }

    public void onHud(GuiGraphics g) {
        if (mc.level == null || mc.player == null) { ViewLock.release(mc); return; }
        ViewLock.update(mc, Hud.steadyView() && anyOverlay());
        if (!Projector.begin(mc)) return;
        Hud.applyTheme();
        Gfx.beginFrame();
        for (Module m : modules) if (m.isEnabled()) m.onHud(g);
    }

    /**
     * Called when the GUI screen itself handled the Right Shift press and closed. Marks the key as
     * already used so the next tick's poll does not see a fresh press and reopen the menu.
     */
    public void consumeGuiKey() { guiKeyWasDown = true; }

    private void pollKeys() {
        if (mc.getWindow() == null) return;
        long win = mc.getWindow().handle();

        boolean gui = GLFW.glfwGetKey(win, dev.kuro.client.util.MenuKey.key()) == GLFW.GLFW_PRESS;
        if (gui && !guiKeyWasDown) {
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(newMenu());
            } else if (mc.screen instanceof ModuleGui gs && !gs.isBinding()) {
                gs.requestClose();   // same key toggles the menu closed (the glass menu fades out first)
            }
        }
        guiKeyWasDown = gui;

        for (Module m : modules) {
            if (m.key <= 0) continue;
            boolean down = GLFW.glfwGetKey(win, m.key) == GLFW.GLFW_PRESS;
            boolean was = wasDown.getOrDefault(m, false);
            if (m.holdToUse()) {
                if (down && !was && mc.screen == null) m.setEnabled(true);
                else if (!down && was) m.setEnabled(false);
            } else if (down && !was && mc.screen == null) m.toggle();
            wasDown.put(m, down);
        }
    }
}
