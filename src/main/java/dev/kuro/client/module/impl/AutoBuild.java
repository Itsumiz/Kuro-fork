package dev.kuro.client.module.impl;

import dev.kuro.client.KuroClient;
import dev.kuro.client.command.CommandManager;
import dev.kuro.client.echo.event.EventStartUseItem;
import dev.kuro.client.echo.event.MouseUpdateEvent;
import dev.kuro.client.event.EventBus;
import dev.kuro.client.event.EventHandler;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.HudElement;
import dev.kuro.client.module.HudPanel;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.BaritoneBridge;
import dev.kuro.client.util.BlockPlacer;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.AssistAim;
import dev.kuro.client.util.RotationEngine;
import dev.kuro.client.util.Rotations;
import dev.kuro.client.util.SchematicInfo;
import dev.kuro.client.util.WorldGfx;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds a schematic with Baritone and keeps itself supplied from a refill chest.
 *
 * Baritone does the hard part (reading the schematic, pathing, placing, breaking). This module wraps it:
 *   1. applies calm movement / camera settings to Baritone (and puts your own settings back when it is switched off),
 *   2. starts the build with "#build <file> x y z",
 *   3. when Baritone runs out of materials (it pauses itself) or your block count drops below a limit, walks to the
 *      refill chest, opens it, takes only the blocks the schematic needs, walks back and resumes the build.
 *
 * Camera modes:  1 Steady   Baritone never touches your camera (all its aiming is server side). Best against motion sickness.
 *                2 Smooth   Baritone turns the real camera with its smooth-look setting; opening the chest turns the camera
 *                           every FRAME (not every tick) at a capped, eased speed, like a slow hand on a mouse.
 *                3 Default  Baritone's own behaviour.
 *
 * Also handled here (Baritone does none of it well):
 *   - Doors / fence gates: when the path ahead goes through a closed wooden door or gate (or the player stands still next to
 *     one) the build is paused, the player turns to it with the same eased aim as the chest, opens it, checks it opened and
 *     the build carries on. "open" is added to Baritone's buildIgnoreProperties so a door you opened is not "fixed" again.
 *   - Signs: the sign editor that pops up after a sign is placed is closed by itself (the sign stays blank).
 */
public class AutoBuild extends Module {
    private static AutoBuild instance;
    public static AutoBuild get() { return instance; }

    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("kuro-autobuild.txt");
    private static final String[] EXTENSIONS = {".schem", ".litematic", ".schematic"};

    // ---------------------------------------------------------------- settings
    private final Setting.Num camera = new Setting.Num("Camera", 1, 1, 3, 1).labelled("Steady (never moves)", "Smooth", "Baritone default");
    private final Setting.Num smoothTicks = new Setting.Num("Smooth look (ticks)", 14, 4, 40, 1);
    private final Setting.Num turnSpeed = new Setting.Num("Chest look speed (deg/s)", 110, 30, 360, 10);
    private final Setting.Bool calm = new Setting.Bool("Calm movement", true);
    /** How fast blocks go down: Baritone's right-click gap in ticks (vanilla = 4). Slow is the default so the build is calm to watch. */
    private final Setting.Num pace = new Setting.Num("Build pace", 3, 1, 4, 1).labelled("Normal", "Relaxed", "Slow", "Very slow");
    private static final int[] PACE_TICKS = {4, 8, 14, 24};
    private final Setting.Bool layers = new Setting.Bool("Build in layers", true);
    private final Setting.Bool lookAtBlocks = new Setting.Bool("Look at the blocks it places / breaks", true);
    private final Setting.Bool openDoors = new Setting.Bool("Open doors / gates in the way", true);
    private final Setting.Bool closeSigns = new Setting.Bool("Close the sign editor automatically", true);
    private final Setting.Num signDelay = new Setting.Num("Sign close delay (ticks)", 4, 1, 20, 1);
    /** How the camera turns to a chest / door: the fixed eased curve, or Aim Assist's hand-on-mouse pull (speed-dependent easing, wobble, mouse steps). */
    private final Setting.Num aimStyle = new Setting.Num("Aim style", 2, 1, 2, 1).labelled("Eased curve", "Assist");
    private final Setting.Num assistSpeed = new Setting.Num("Assist aim speed", 5, 1, 10, 1);
    private final AssistAim assist = new AssistAim();
    private final Setting.Num aimTime = new Setting.Num("Aim smoothness (min turn time, ms)", 280, 100, 900, 20);
    private final Setting.Num lowBlocks = new Setting.Num("Refill below (blocks, 0 = only when empty)", 0, 0, 640, 16);
    private final Setting.Num scaffold = new Setting.Num("Scaffold blocks to take (dirt / cobble, 0 = none)", 64, 0, 256, 16);
    private final Setting.Num stacks = new Setting.Num("Max stacks per trip", 27, 1, 36, 1);
    private final Setting.Num keepFree = new Setting.Num("Free slots to keep", 2, 0, 8, 1);
    private final Setting.Num clickDelay = new Setting.Num("Chest click delay (ticks)", 3, 1, 10, 1);
    private final Setting.Bool relaxed = new Setting.Bool("Relaxed watch mode (slow, smooth, calm)", true);
    private final Setting.Bool chestFacing = new Setting.Bool("Accept any chest facing (stops break + replace loop)", true);
    private final Setting.Bool doubleChests = new Setting.Bool("Allow double chests (ignore chest side)", true);
    private final Setting.Bool showProgress = new Setting.Bool("Show build progress (bar + %)", true);
    private final Setting.Bool showNext = new Setting.Bool("Show next 10 blocks", true);
    private final Setting.Bool showGhost = new Setting.Bool("Show whole schematic (ghost)", true);
    private final Setting.Num ghostOpacity = new Setting.Num("Ghost opacity (%)", 20, 5, 60, 5);
    private final Setting.Num ghostRange = new Setting.Num("Ghost range (blocks)", 32, 8, 64, 4);
    private static final int GHOST_MAX = 6000;
    private final int[] ghostIdx = new int[GHOST_MAX];
    private int ghostCount;
    private final Setting.Bool showOutline = new Setting.Bool("Show schematic outline", true);
    private final Setting.Bool esp = new Setting.Bool("Show chest / origin boxes", true);

    // ---------------------------------------------------------------- saved choices
    private String file;
    private BlockPos origin;
    private BlockPos chest;
    private volatile SchematicInfo info;
    private volatile dev.kuro.client.util.SchematicLayout layoutLoaded;
    private volatile Path layoutPath;
    private dev.kuro.client.util.BuildProgress progress;
    private java.util.List<BlockPos> nextBlocks = java.util.List.of();
    private int progressTick, lastDone, stallTicks, restarts;
    private java.util.Map<Item, Integer> needNow;                 // still missing in the build, per item (null = unknown)
    private final java.util.Map<Item, Integer> takenItems = new java.util.LinkedHashMap<>();

    // ---------------------------------------------------------------- run state
    private enum S { IDLE, BUILDING, TO_CHEST, OPENING, LOOTING, LOOK_BACK, DOOR }
    private S state = S.IDLE;
    private int stateTicks, grace, retryWait, walkRetries, openAttempts, clickWait, clickCd, taken, freeAtOpen;
    private boolean started, pendingOff, picking, wasActive, apiOk = true;
    private final List<String> cmdSettings = new ArrayList<>();
    private final Set<Integer> tried = new HashSet<>();
    private float savedYaw, savedPitch;

    // ---------------------------------------------------------------- door / sign helpers
    private BlockPos doorPos;                                      // the door being opened (state DOOR)
    private int doorTries, doorCooldown, stuckTicks, signWait, silentDoorCd;
    private double lastX, lastZ;
    private boolean doorPausedBuilder;
    private final Map<BlockPos, Integer> doorGiveUp = new java.util.HashMap<>();   // door -> tick it may be tried again
    private final Map<BlockPos, Integer> doorFails = new java.util.HashMap<>();    // door -> clicks that did not open it
    private int clock;

    // ---------------------------------------------------------------- per-frame camera aim
    private boolean aiming, aimDone;
    private float aimYaw, aimPitch;
    private float aimStartYaw, aimStartPitch;                      // where the current turn began
    private long aimStartNs;                                       // when it began
    private float aimDurationS;                                    // how long it takes (minimum-jerk curve); < 0 = restart the turn

    private record ClickAim(float yaw, float pitch, BlockHitResult hit) {}

    public AutoBuild() {
        super("Auto Build", Category.PLAYER);
        instance = this;

        add(new Setting.Button("Choose schematic... (list, click one)", () -> mc.setScreen(new dev.kuro.client.gui.SchematicPickerScreen(mc.screen, this))));
        add(new Setting.Button("Open schematics folder", AutoBuild::openFolder));
        add(new Setting.Text("Schematic file (Enter)", this::setFile));
        add(new Setting.Button("Pick refill chest (then right-click it)", () -> { armPicker(); mc.execute(() -> mc.setScreen(null)); }));
        add(new Setting.Button("Refill chest = block I look at", this::pickLooked));
        add(new Setting.Button("Origin = where I stand", this::setOriginHere));
        add(new Setting.Button("List schematics", this::listFiles));
        add(new Setting.Button("Materials list... (tick off)", () -> mc.setScreen(new dev.kuro.client.gui.MaterialsScreen(mc.screen, this))));
        add(new Setting.Button("Show materials (in chat)", this::showMaterials));
        add(new Setting.Button("Show status", this::status));
        add(camera);
        add(smoothTicks);
        add(calm);
        add(pace);
        add(layers);
        add(lookAtBlocks);
        add(openDoors);
        add(closeSigns);
        add(signDelay);
        add(lowBlocks);
        add(scaffold);
        add(stacks);
        add(keepFree);
        add(clickDelay);
        add(turnSpeed);
        add(aimStyle);
        add(assistSpeed);
        add(aimTime);
        add(relaxed);
        add(doubleChests);
        add(chestFacing);
        add(showProgress);
        add(showNext);
        add(showGhost);
        add(ghostOpacity);
        add(ghostRange);
        add(showOutline);
        add(esp);
        load();
    }

    // ================================================================== enable / disable

    @Override
    protected void onEnable() {
        state = S.IDLE; started = false; pendingOff = false; aiming = false; aimDone = false; wasActive = false;
        stateTicks = 0; walkRetries = 0; openAttempts = 0; retryWait = 0;
        doorPos = null; doorTries = 0; doorCooldown = 0; stuckTicks = 0; signWait = 0; clock = 0; doorPausedBuilder = false; doorGiveUp.clear(); doorFails.clear();
        String problem = checkReady();
        if (problem != null) { say(problem); pendingOff = true; return; }

        Path path = resolveFile(file);
        String name = schematicsDir().relativize(path).toString().replace('\\', '/');
        if (name.matches(".*\\s.*")) {
            say("The file name has a space in it. Rename it (e.g. my_house.schem): Baritone's #build cannot take spaces.");
            pendingOff = true;
            return;
        }
        loadInfoAsync(path);
        loadLayoutAsync(path);
        progress = null; ghostCount = 0; nextBlocks = java.util.List.of(); progressTick = 0; lastDone = 0; stallTicks = 0; restarts = 0;
        apiOk = BaritoneBridge.apiWorks();
        if (!apiOk) say("Could not read Baritone's API (" + BaritoneBridge.lastError() + "). Using chat commands instead; progress is judged from the world.");
        cmdSettings.clear();
        applyBaritone();
        startBuild();
        started = true;
        if (scaffold.intValue() > 0 && throwawayCount() == 0)
            say("You carry no scaffold blocks (dirt / cobblestone / netherrack). Baritone needs some to bridge and pillar: the refill will take " + scaffold.intValue() + ".");
        grace = 60;
        enter(S.BUILDING);
        say("Building " + name + " at " + fmt(origin) + ". Refill chest: " + fmt(chest) + ".");
    }

    @Override
    protected void onDisable() {
        stopAim();
        Rotations.release(this);
        if (started) {
            if (!BaritoneBridge.cancelEverything()) bcmd("cancel");
            started = false;
        }
        BaritoneBridge.restoreAll();
        for (String n : cmdSettings) bcmd("set reset " + n);
        cmdSettings.clear();
        if (mc.player != null && (state == S.OPENING || state == S.LOOTING) && mc.screen instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
        state = S.IDLE;
        doorPos = null; doorPausedBuilder = false;
        pendingOff = false;
        picking = false;                                           // switching off also cancels a pending chest pick
    }

    /** (Re)starts the Baritone build of the chosen schematic. Baritone compares with the world, so this also resumes after a refill. */
    private void startBuild() {
        Path path = resolveFile(file);
        if (path == null || origin == null) return;
        String name = schematicsDir().relativize(path).toString().replace('\\', '/');
        bcmd("cancel");
        bcmd("build " + name + " " + origin.getX() + " " + origin.getY() + " " + origin.getZ());
    }

    private String checkReady() {
        if (mc.level == null || mc.player == null) return "Join a world first.";
        if (!BaritoneBridge.installed()) return "Baritone is not installed. Put baritone-standalone-fabric in your mods folder.";
        if (file == null) return "Choose a schematic first: .build file <name>   (see .build list)";
        if (resolveFile(file) == null) return "Schematic \"" + file + "\" not found in " + schematicsDir() + "  (see .build list)";
        if (origin == null) return "Set the build origin first: stand where the schematic's corner goes and run .build origin";
        if (chest == null) return "Choose the refill chest first: look at it and run .build chest   (or press Pick refill chest)";
        if (!isContainer(chest)) return "The saved refill chest at " + fmt(chest) + " is not a chest / barrel / shulker box any more (or is not loaded).";
        return null;
    }

    // ================================================================== Baritone settings

    /** Relaxed watch mode forces the visible smooth camera; otherwise the Camera setting decides. */
    private int camMode() { return relaxed.value ? 2 : camera.intValue(); }
    private int lookTicks() { return relaxed.value ? Math.max(smoothTicks.intValue(), 26) : smoothTicks.intValue(); }
    private int chestTurnSpeed() { return relaxed.value ? Math.min(turnSpeed.intValue(), 100) : turnSpeed.intValue(); }

    private void applyBaritone() {
        List<String> missing = new ArrayList<>();
        bset(missing, "allowInventory", true);                     // lets Baritone pull blocks from the inventory onto the hotbar
        bset(missing, "buildInLayers", layers.value);
        applyIgnoreProps(false, false);                            // "open" (doors / gates you opened) + the chest fixes
        switch (camMode()) {
            case 1 -> {                                            // Steady: walking never turns the camera
                bset(missing, "freeLook", true);
                if (lookAtBlocks.value) {                          // ...but placing / breaking turns it to the block, eased
                    bset(missing, "blockFreeLook", false);
                    bset(missing, "smoothLook", true);
                    bset(missing, "smoothLookTicks", lookTicks());
                } else {
                    bset(missing, "blockFreeLook", true);
                    bset(missing, "smoothLook", false);
                }
            }
            case 2 -> {                                            // Smooth: the real camera turns to face where you walk and to each block it works on, eased
                bset(missing, "freeLook", false);                  // false = your view follows the way you move (true kept it frozen)
                bset(missing, "blockFreeLook", false);
                bset(missing, "smoothLook", true);
                bset(missing, "smoothLookTicks", lookTicks());
            }
            default -> {}
        }
        if (relaxed.value) {
            bset(missing, "randomLooking", 0.0);                   // no random head twitching
            bset(missing, "randomLooking113", 0.0);
            bset(missing, "blockPlacementPenalty", 40.0);          // prefers a steady walk over awkward hops
        }
        int paceIdx = Math.max(1, Math.min(4, pace.intValue()));
        bset(missing, "rightClickSpeed", PACE_TICKS[paceIdx - 1]);   // ticks between block placements
        if (paceIdx >= 2) bset(missing, "walkWhileBreaking", false); // stand still while breaking: calmer
        if (calm.value || relaxed.value) {
            bset(missing, "allowSprint", false);
            bset(missing, "allowParkour", false);
            bset(missing, "allowParkourPlace", false);
            bset(missing, "allowParkourAscend", false);
            bset(missing, "jumpPenalty", 6.0);
        }
        if (!missing.isEmpty()) say("This Baritone version has no setting: " + String.join(", ", missing) + " (skipped).");
    }

    private boolean cmdSet(String name, String value) { bcmd("set " + name + " " + value); cmdSettings.add(name); return true; }

    private void bset(List<String> missing, String name, Object value) {
        if (BaritoneBridge.set(name, value)) return;
        if (!apiOk) { bcmd("set " + name + " " + value); cmdSettings.add(name); return; }   // same thing typed as #set
        missing.add(name);
    }

    private static void bcmd(String command) {
        for (Module m : KuroClient.modules.modules) {
            if (m instanceof BaritoneControl b) { b.cmd(command); return; }
        }
    }

    // ================================================================== main state machine

    private void enter(S next) { state = next; stateTicks = 0; }

    @Override
    public void onPreTick() {
        if (pendingOff) { pendingOff = false; setEnabled(false); return; }
        if (mc.player == null || mc.level == null || mc.gameMode == null) { setEnabled(false); return; }
        stateTicks++;
        clock++;
        tickSign();                                                // first: an open sign editor blocks everything else
        tickProgress();                                            // always: the refill and the chest fix use it too
        switch (state) {
            case DOOR -> tickDoor();
            case BUILDING -> tickBuilding();
            case TO_CHEST -> tickToChest();
            case OPENING -> tickOpening();
            case LOOTING -> tickLooting();
            case LOOK_BACK -> tickLookBack();
            default -> {}
        }
    }

    private void tickBuilding() {
        if (grace > 0) { grace--; return; }
        if (checkDoor()) return;                                   // a closed door / gate ahead: open it first
        if (!apiOk) {                                              // no API: judge from the world and the inventory
            wasActive = true;
            if (progress != null && progress.total() > 0 && progress.missing() == 0 && progress.done() > 0) {
                say("Build finished.");
                setEnabled(false);
                return;
            }
            if (stateTicks > 100 && stateTicks % 20 == 0 && progress != null && mc.player != null) {
                java.util.List<BlockPos> nx = progress.next(1, mc.player.getX(), mc.player.getY(), mc.player.getZ(), layers.value);
                Block want = nx.isEmpty() ? null : progress.targetAt(nx.get(0));
                Item it = want == null ? null : want.asItem();
                if (it != null && it != net.minecraft.world.item.Items.AIR && inInventory(it) == 0) {
                    startTrip("Out of " + new ItemStack(it).getHoverName().getString());
                    return;
                }
            }
            if (progress != null) {                                // Baritone idle but we think it builds: restart it
                if (progress.done() != lastDone) { lastDone = progress.done(); stallTicks = 0; restarts = 0; }
                else if (stateTicks > 100 && ++stallTicks >= 400) {
                    stallTicks = 0;
                    if (scaffold.intValue() > 0 && throwawayCount() == 0) { startTrip("Out of scaffold blocks"); return; }
                    if (++restarts > 4) { fail("Baritone is not making progress. Read its messages in chat (out of scaffold blocks? a spot it cannot reach?)."); return; }
                    say("No progress for 20 seconds: restarting Baritone's build (" + restarts + " / 4).");
                    startBuild();
                    grace = 40;
                    return;
                }
            }
            int lowF = Math.max(1, lowBlocks.intValue());
            if (stateTicks > 100 && lowBlocks.intValue() > 0 && blocksInInventory() < lowF) startTrip("Running low on blocks");
            return;
        }
        if (!BaritoneBridge.builderActive()) {
            say(!wasActive && stateTicks < 200 ? "Baritone did not start the build. Check its message in chat (file name? origin?)."
                                               : "Build finished.");
            started = false;                                       // nothing left to cancel
            setEnabled(false);
            return;
        }
        wasActive = true;
        if (BaritoneBridge.builderPaused()) { startTrip("Baritone is out of materials"); return; }
        int low = lowBlocks.intValue();
        if (low > 0 && blocksInInventory() < low) {
            BaritoneBridge.pauseBuilder();
            startTrip("Running low on blocks");
        }
    }

    private java.util.Map<Item, Integer> remainingNeeds() {
        dev.kuro.client.util.BuildProgress bp = progress;
        if (bp == null) return null;
        java.util.Map<Item, Integer> out = new java.util.HashMap<>();
        for (Map.Entry<Block, Integer> e : bp.missingBlocks().entrySet()) {
            Item it = e.getKey().asItem();
            if (it != net.minecraft.world.item.Items.AIR) out.merge(it, e.getValue(), Integer::sum);
        }
        return out.isEmpty() ? null : out;
    }

    private int inInventory(Item it) {
        int n = 0;
        for (int i = 0; i < 36; i++) { ItemStack s = mc.player.getInventory().getItem(i); if (s.getItem() == it) n += s.getCount(); }
        return n;
    }

    private void startTrip(String reason) {
        needNow = remainingNeeds();
        takenItems.clear();
        say(reason + ": walking to the refill chest.");
        walkRetries = 0; retryWait = 0;
        if (!apiOk) {
            bcmd("cancel");
            bcmd("goto " + chest.getX() + " " + chest.getY() + " " + chest.getZ());
            enter(S.TO_CHEST);
            return;
        }
        if (!BaritoneBridge.goToBlock(chest)) { fail("Could not start walking to the chest."); return; }
        enter(S.TO_CHEST);
    }

    private void tickToChest() {
        if (stateTicks < 10) return;                               // the goal needs a few ticks to start
        checkDoor();                                               // walking to the chest: doors are opened without stopping
        if (retryWait > 0) { retryWait--; return; }
        if (!apiOk) {
            double dd = mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(chest));
            if (dd <= 4.5) { bcmd("cancel"); openAttempts = 0; clickWait = 0; enter(S.OPENING); }
            else if (stateTicks > 3600) fail("Took too long to reach the refill chest.");
            return;
        }
        if (!BaritoneBridge.goalActive()) {
            double d = mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(chest));
            if (d <= 5.2) { openAttempts = 0; clickWait = 0; enter(S.OPENING); return; }
            if (++walkRetries > 3) { fail("Could not reach the refill chest (no path?)."); return; }
            BaritoneBridge.goToBlock(chest);
            retryWait = 10;
            return;
        }
        if (stateTicks > 3600) fail("Took too long to reach the refill chest.");
    }

    private void tickOpening() {
        if (chestMenuSize() > 0) { tried.clear(); taken = 0; clickCd = 0; freeAtOpen = freeSlots(); enter(S.LOOTING); return; }
        if (mc.screen != null) { if (stateTicks > 80) fail("Another screen is in the way."); return; }
        if (stateTicks > 240 || openAttempts > 4) { fail("The refill chest would not open (is something on top of it?)."); return; }
        Player p = mc.player;
        if (p.isShiftKeyDown()) return;                            // sneaking with a block in hand skips the chest
        if (clickWait > 0) { clickWait--; return; }

        ClickAim aim = findAim(chest);
        if (aim == null) { if (stateTicks > 40) fail("No clear line to the refill chest from here."); return; }

        if (camMode() == 1 || stateTicks > 100) {          // steady, or the window has no focus so the camera never turned
            if (silentClick(aim)) { openAttempts++; clickWait = 12; }
            return;
        }
        if (!aiming) { savedYaw = p.getYRot(); savedPitch = p.getXRot(); }
        setAim(aim.yaw, aim.pitch);
        if (aimDone) {
            BlockHitResult hr = viewHit(chest);
            if (hr != null) { click(hr); openAttempts++; clickWait = 12; }
            else reaim();                                          // not on it exactly: turn again from here
        }
    }

    private void tickLooting() {
        int size = chestMenuSize();
        if (size <= 0) { if (stateTicks > 20) finishTrip(); return; }   // the chest was closed from outside
        if (clickCd > 0) { clickCd--; return; }
        AbstractContainerMenu menu = ((AbstractContainerScreen<?>) mc.screen).getMenu();
        if (taken >= stacks.intValue() || freeSlots() <= keepFree.intValue()) { closeAndFinish(); return; }
        for (int i = 0; i < size; i++) {
            if (!tried.add(i)) continue;                           // each slot once: a full inventory cannot loop forever
            ItemStack st = menu.getSlot(i).getItem();
            if (st.isEmpty() || !wanted(st)) continue;
            takenItems.merge(st.getItem(), st.getCount(), Integer::sum);
            mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
            taken++;
            clickCd = Math.max(0, clickDelay.intValue() - 1);
            return;
        }
        closeAndFinish();                                          // every slot looked at
    }

    private void closeAndFinish() {
        mc.player.closeContainer();
        finishTrip();
    }

    private void finishTrip() {
        if (taken == 0) {
            fail(freeAtOpen <= keepFree.intValue()
                    ? "Your inventory is full: free some slots or lower \"Free slots to keep\"."
                    : "The refill chest has nothing this schematic needs. Stopping.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<Item, Integer> e : takenItems.entrySet()) {
            if (shown++ >= 8) { sb.append(", ..."); break; }
            sb.append(shown > 1 ? ", " : "").append(new ItemStack(e.getKey()).getHoverName().getString()).append(" x").append(e.getValue());
        }
        say("Took " + taken + " stack" + (taken == 1 ? "" : "s") + " (" + sb + "). Back to building.");
        java.util.Map<Item, Integer> need = needNow;
        if (need != null) {
            StringBuilder lack = new StringBuilder();
            int c = 0;
            for (Map.Entry<Item, Integer> e : need.entrySet()) {
                int missing = e.getValue() - inInventory(e.getKey());
                if (missing <= 0 || c >= 6) continue;
                lack.append(c++ > 0 ? ", " : "").append(new ItemStack(e.getKey()).getHoverName().getString()).append(" x").append(missing);
            }
            if (c > 0) say("Still not in your inventory (not in the chest, or no room): " + lack);
        }
        if (camMode() != 1 && aiming) setAim(savedYaw, savedPitch);   // ease the camera back to where you were looking
        enter(S.LOOK_BACK);
    }

    private void tickLookBack() {
        if (stateTicks < 6) return;                                // let the server settle after the chest closes
        if (aiming && !aimDone && stateTicks < 140) return;
        stopAim();
        if (!apiOk) startBuild();                                  // Baritone rebuilds whatever is still missing
        else if (!BaritoneBridge.resumeBuilder()) bcmd("resume");
        grace = 40;
        enter(S.BUILDING);
    }

    private void fail(String message) {
        say(message);
        say("Auto Build stopped.");
        pendingOff = true;
    }

    // ================================================================== signs

    /** The sign editor opens by itself after a sign is placed and blocks the build: close it after a short human pause. */
    private void tickSign() {
        if (!closeSigns.value || !(mc.screen instanceof AbstractSignEditScreen)) { signWait = 0; return; }
        if (++signWait >= signDelay.intValue()) {
            signWait = 0;
            mc.screen.onClose();                                   // same as pressing Escape: the sign stays blank
        }
    }

    // ================================================================== doors / fence gates

    /** A wooden / copper door or a fence gate that is shut. Iron doors need redstone, so they are left alone. */
    private static boolean isClosedDoor(BlockPos pos) {
        if (mc.level == null) return false;
        BlockState st = mc.level.getBlockState(pos);
        Block b = st.getBlock();
        if (b == Blocks.IRON_DOOR) return false;
        if (!(b instanceof DoorBlock) && !(b instanceof FenceGateBlock)) return false;
        return st.hasProperty(BlockStateProperties.OPEN) && !st.getValue(BlockStateProperties.OPEN);
    }

    /** A closed door / gate on the path ahead (or right next to a player who has stopped moving), within reach. */
    private BlockPos findBlockingDoor() {
        Player p = mc.player;
        List<BlockPos> cands = new ArrayList<>();
        for (BlockPos q : BaritoneBridge.upcomingPath(5)) { cands.add(q); cands.add(q.above()); }
        if (stuckTicks >= 25) {                                    // path unknown or still blocked: whatever is touching us
            BlockPos me = p.blockPosition();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { cands.add(me.offset(dx, 0, dz)); cands.add(me.offset(dx, 1, dz)); }
        }
        Vec3 eye = p.getEyePosition();
        for (BlockPos c : cands) {
            if (!isClosedDoor(c)) continue;
            Integer until = doorGiveUp.get(c);
            if (until != null && clock < until) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(c)) > 4.0 * 4.0) continue;       // not close enough yet
            return c.immutable();
        }
        return null;
    }

    private void doorFailed(BlockPos door) {
        doorGiveUp.put(door.immutable(), clock + 600);             // leave it alone for 30 seconds
        say("Could not open the door / gate at " + fmt(door) + " (locked, blocked or out of reach). Open it by hand if the build stops.");
    }

    /**
     * Called every tick while walking. Returns true when it took over (state DOOR), so the caller stops for this tick.
     * Camera modes Smooth / Default stop the builder and turn the real camera to the door like to the chest; in Steady mode
     * (and while walking to the chest) the door is clicked with a silent look packet and the walk goes on.
     */
    private boolean checkDoor() {
        if (!openDoors.value || mc.player == null || mc.level == null) return false;
        Player p = mc.player;
        double mx = p.getX() - lastX, mz = p.getZ() - lastZ;
        lastX = p.getX(); lastZ = p.getZ();
        stuckTicks = Math.hypot(mx, mz) < 0.02 ? stuckTicks + 1 : 0;
        if (doorCooldown > 0) { doorCooldown--; return false; }
        if (mc.screen != null || p.isShiftKeyDown() || p.isUsingItem() || !p.mayBuild() || p.isSpectator()) return false;
        if (stuckTicks < 25 && clock % 4 != 0) return false;      // look every 4th tick, every tick once we are stuck
        BlockPos door = findBlockingDoor();
        if (door == null) return false;

        if (state == S.BUILDING && apiOk && camMode() != 1 && BaritoneBridge.builderActive() && !BaritoneBridge.builderPaused()) {
            doorPos = door;
            doorTries = 0; clickWait = 0;
            doorPausedBuilder = BaritoneBridge.pauseBuilder();
            enter(S.DOOR);
            return true;
        }
        ClickAim aim = findAim(door);                              // silent version
        int fails = doorFails.merge(door, 1, Integer::sum);
        if (aim == null || fails > 3) { doorFailed(door); doorFails.remove(door); return false; }
        if (silentClick(aim)) doorCooldown = 10;
        return false;
    }

    private void tickDoor() {
        Player p = mc.player;
        if (doorPos == null) { endDoor(); return; }
        if (mc.screen != null) { if (stateTicks > 60) endDoor(); return; }
        if (!isClosedDoor(doorPos)) {                              // open now (our click came back from the server), or gone
            if (doorTries > 0 && stateTicks < 200) { doorFails.remove(doorPos); }
            endDoor();
            return;
        }
        if (stateTicks > 200 || doorTries >= 3) { doorFailed(doorPos); endDoor(); return; }
        if (p.isShiftKeyDown()) return;
        if (stateTicks < 3) return;                                // let the walk settle after the pause
        if (clickWait > 0) { clickWait--; return; }

        ClickAim aim = findAim(doorPos);
        if (aim == null) {
            if (stateTicks > 40) { doorFailed(doorPos); endDoor(); }
            return;
        }
        if (stateTicks > 70) {                                     // the camera never turned (window without focus): silent click
            if (silentClick(aim)) { doorTries++; clickWait = 10; }
            return;
        }
        setAim(aim.yaw, aim.pitch);
        if (aimDone) {
            BlockHitResult hr = viewHit(doorPos);
            if (hr != null) { click(hr); doorTries++; clickWait = 10; }
            else reaim();
        }
    }

    private void endDoor() {
        stopAim();
        if (doorPausedBuilder) {
            if (!BaritoneBridge.resumeBuilder()) bcmd("resume");
            doorPausedBuilder = false;
        }
        doorPos = null;
        grace = 30;                                                // give Baritone a moment to re-plan through the open door
        enter(S.BUILDING);
    }

    // ================================================================== chest interaction

    /**
     * Finds where to look to click a block: points spread over the block's real outline (a door is a thin slab on one edge,
     * so the middle of the 1x1x1 cube would miss it), each checked with the same ray the game casts. Of the points that hit,
     * the one that needs the smallest turn from where you look now wins (the middle is preferred when it is about as close).
     */
    private ClickAim findAim(BlockPos target) {
        Player p = mc.player;
        Vec3 eye = p.getEyePosition();
        net.minecraft.world.phys.shapes.VoxelShape shape = mc.level.getBlockState(target).getShape(mc.level, target);
        net.minecraft.world.phys.AABB box = shape.isEmpty() ? new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        Vec3 c = new Vec3(target.getX() + (box.minX + box.maxX) / 2, target.getY() + (box.minY + box.maxY) / 2, target.getZ() + (box.minZ + box.maxZ) / 2);
        double sx = box.maxX - box.minX, sy = box.maxY - box.minY, sz = box.maxZ - box.minZ;
        double[] f = {0.0, 0.2, -0.2, 0.35, -0.35};                // fractions of the outline size, centre first
        ClickAim best = null;
        float bestCost = Float.MAX_VALUE;
        for (double fx : f) for (double fy : f) for (double fz : f) {
            Vec3 dir = c.add(fx * sx, fy * sy, fz * sz).subtract(eye);
            double len = dir.length();
            if (len < 1.0E-4) continue;
            Vec3 end = eye.add(dir.scale(BlockPlacer.REACH / len));
            BlockHitResult hr = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hr.getType() != HitResult.Type.BLOCK || !hr.getBlockPos().equals(target)) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
            float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z))), -90f, 90f);
            float turn = (float) Math.hypot(Mth.wrapDegrees(yaw - p.getYRot()), pitch - p.getXRot());
            float cost = turn + (float) (Math.abs(fx) + Math.abs(fy) + Math.abs(fz)) * 6f;   // small pull towards the middle
            if (cost < bestCost) { bestCost = cost; best = new ClickAim(yaw, pitch, hr); }
        }
        return best;
    }

    /** What the crosshair hits right now, if that is the given block. */
    private BlockHitResult viewHit(BlockPos target) {
        Player p = mc.player;
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(p.getXRot(), p.getYRot()).scale(BlockPlacer.REACH));
        BlockHitResult hr = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hr.getType() == HitResult.Type.BLOCK && hr.getBlockPos().equals(target) ? hr : null;
    }

    private void click(BlockHitResult hr) {
        InteractionResult r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hr);
        if (r instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** Steady mode: the server is told the aim with a look packet, your own camera never moves (same method as BlockPlacer.placeAt). */
    private boolean silentClick(ClickAim aim) {
        Player p = mc.player;
        if (!Rotations.free() && !Rotations.ownedBy(this)) return false;
        if (!Rotations.set(this, aim.yaw, aim.pitch) || !Rotations.sendLookPacket(true)) { Rotations.release(this); return false; }
        float oldYaw = p.getYRot(), oldPitch = p.getXRot();
        p.setYRot(aim.yaw);
        p.setXRot(aim.pitch);
        try { click(aim.hit); }
        finally { p.setYRot(oldYaw); p.setXRot(oldPitch); Rotations.release(this); }
        return true;
    }

    private int chestMenuSize() {
        if (!(mc.screen instanceof AbstractContainerScreen<?> hs)) return -1;
        AbstractContainerMenu m = hs.getMenu();
        if (m instanceof ChestMenu g) return g.getRowCount() * 9;
        if (m instanceof ShulkerBoxMenu) return 27;
        return -1;
    }

    /** Only what the schematic needs when its materials are known, otherwise any block item. */
    /** Blocks Baritone uses for scaffolding / pillaring / bridging (its default acceptableThrowawayItems). */
    private static boolean isThrowaway(Item it) {
        return it == net.minecraft.world.item.Items.DIRT || it == net.minecraft.world.item.Items.COBBLESTONE
                || it == net.minecraft.world.item.Items.NETHERRACK || it == net.minecraft.world.item.Items.STONE
                || it == net.minecraft.world.item.Items.COBBLED_DEEPSLATE;
    }

    private int throwawayCount() {
        int n = 0;
        for (int i = 0; i < 36; i++) { ItemStack s = mc.player.getInventory().getItem(i); if (isThrowaway(s.getItem())) n += s.getCount(); }
        return n;
    }

    private boolean wanted(ItemStack st) {
        if (state == S.LOOTING && scaffold.intValue() > 0 && isThrowaway(st.getItem()) && throwawayCount() < scaffold.intValue()) return true;
        java.util.Map<Item, Integer> need = needNow;
        if (need != null && state == S.LOOTING) {                  // only what the build still lacks, and not more than it lacks
            Integer n = need.get(st.getItem());
            return n != null && inInventory(st.getItem()) < n;
        }
        return wantedStatic(st);
    }

    private boolean wantedStatic(ItemStack st) {
        SchematicInfo si = info;
        if (si != null && !si.items.isEmpty()) return si.items.containsKey(st.getItem());
        return st.getItem() instanceof net.minecraft.world.item.BlockItem;
    }

    private int freeSlots() {
        int n = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    private int blocksInInventory() {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && wantedStatic(s)) n += s.getCount();
        }
        return n;
    }

    private static boolean isContainer(BlockPos pos) {
        if (mc.level == null) return false;
        Block b = mc.level.getBlockState(pos).getBlock();
        return b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock || b instanceof EnderChestBlock;
    }

    // ================================================================== smooth camera (every frame, not every tick)

    /**
     * Starts (or keeps) a turn to yaw / pitch. The turn follows a minimum-jerk curve: speed rises from nothing, peaks in the
     * middle and falls to nothing exactly on the target, which is how a hand moves a mouse. How long it takes depends on the
     * distance (peak speed = "Chest look speed", never quicker than "Aim smoothness"), and each turn is a little different
     * in length. Small changes of the aim point (under 1 degree) do not restart the turn.
     */
    private void setAim(float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90f, 90f);
        Player p = mc.player;
        boolean same = aiming && aimDurationS > 0f
                && Math.abs(Mth.wrapDegrees(yaw - aimYaw)) <= 1.0f && Math.abs(pitch - aimPitch) <= 1.0f;
        if (same) return;
        aimStartYaw = p.getYRot();
        aimStartPitch = p.getXRot();
        aimYaw = aimStartYaw + Mth.wrapDegrees(yaw - aimStartYaw);       // the short way round
        aimPitch = pitch;
        float dist = (float) Math.hypot(aimYaw - aimStartYaw, aimPitch - aimStartPitch);
        float peak = Math.max(10f, chestTurnSpeed());
        float variation = 0.92f + 0.2f * java.util.concurrent.ThreadLocalRandom.current().nextFloat();
        aimDurationS = Math.max((float) (aimTime.value / 1000.0), 1.875f * dist / peak) * variation;
        aimStartNs = System.nanoTime();
        aimDone = dist < 0.4f;
        aiming = true;
    }

    /** Throws the current turn away and starts a new one from where the camera is now (used when the click would miss). */
    private void reaim() { aimDurationS = -1f; aimDone = false; }

    private void stopAim() {
        aiming = false; aimDone = false; aimDurationS = 0f;
        assist.reset();
    }

    /**
     * Runs once per rendered frame, right where a real mouse move would be applied, and places the camera on the curve for
     * this exact moment (so it is smooth at any frame rate, with no sudden start or stop).
     */
    @SuppressWarnings("unused")
    @EventHandler
    private void onMouseUpdate(MouseUpdateEvent event) {
        if (!aiming || mc.player == null || mc.screen != null || aimDurationS <= 0f) return;
        if (aimStyle.intValue() == 2) { assistTurn(); return; }
        float t = Mth.clamp((System.nanoTime() - aimStartNs) / 1.0E9f / aimDurationS, 0f, 1f);
        float s = t * t * t * (t * (t * 6f - 15f) + 10f);                // minimum-jerk position profile

        Player p = mc.player;
        float yaw = p.getYRot(), pitch = p.getXRot();
        float newYaw = aimStartYaw + (aimYaw - aimStartYaw) * s;
        float newPitch = Mth.clamp(aimStartPitch + (aimPitch - aimStartPitch) * s, -90f, 90f);

        p.setYRot(newYaw);
        p.setXRot(newPitch);
        p.yHeadRot = newYaw;
        p.yRotO += newYaw - yaw;                                   // same bookkeeping as a real mouse move
        p.xRotO += newPitch - pitch;
        if (t >= 1f) aimDone = true;
    }

    /** Assist style: one frame of Aim Assist's pull towards the current aim angles (a point far along that direction). */
    private void assistTurn() {
        Player p = mc.player;
        float yawErr = Mth.wrapDegrees(aimYaw - p.getYRot()), pitchErr = aimPitch - p.getXRot();
        if (Math.hypot(yawErr, pitchErr) < 0.4) { aimDone = true; return; }
        aimDone = false;
        Vec3 point = p.getEyePosition().add(Vec3.directionFromRotation(aimPitch, aimYaw).scale(16.0));
        float[] d = assist.stepToPoint(point, p.getYRot(), p.getXRot(), assistSpeed.value, assistSpeed.value, true, 1f, 1f);
        if (d != null) {
            // applyTrackedMouseDelta takes mouse units: yaw += x * 0.15, pitch -= y * 0.15
            RotationEngine.PlayerMouseRotationApplier.applyTrackedMouseDelta(d[0] / 0.15f, -d[1] / 0.15f);
        }
    }

    // ================================================================== picking the chest by right-clicking it

    private void armPicker() {
        picking = true;
        EventBus.INSTANCE.register(this);
        say("Right-click the refill chest (chest, barrel or shulker box). It will not open.");
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onUse(EventStartUseItem.Pre event) {
        if (!picking) return;
        BlockHitResult hr = event.getBlockHitResult();
        if (hr == null) return;
        event.cancel();
        if (!isContainer(hr.getBlockPos())) { say("That is not a chest, barrel or shulker box. Try again."); return; }
        picking = false;
        if (!isEnabled()) EventBus.INSTANCE.unregister(this);
        setChest(hr.getBlockPos());
    }

    private void pickLooked() {
        if (mc.player == null || mc.level == null) return;
        Player p = mc.player;
        Vec3 eye = p.getEyePosition();
        BlockHitResult hr = mc.level.clip(new ClipContext(eye, eye.add(p.getViewVector(1.0f).scale(6.0)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hr.getType() != HitResult.Type.BLOCK || !isContainer(hr.getBlockPos())) {
            say("Look at a chest, barrel or shulker box first (within 6 blocks), then run this again.");
            return;
        }
        setChest(hr.getBlockPos());
    }

    private void setChest(BlockPos pos) {
        chest = pos.immutable();
        save();
        say("Refill chest set to " + fmt(chest) + ".");
    }

    private void setOriginHere() {
        if (mc.player == null) return;
        origin = mc.player.blockPosition().immutable();
        save();
        say("Build origin set to " + fmt(origin) + " (the schematic's corner goes here).");
    }

    // ================================================================== files / info

    /** Folder the schematics live in (created on demand). */
    public static Path folder() { return schematicsDir(); }

    public String selectedFile() { return file; }

    /** The materials of the chosen file for the materials screen; starts a quiet background read if it is not loaded yet. */
    public SchematicInfo materialsInfo() {
        SchematicInfo si = info;
        if (si != null) return si;
        Path p = resolveFile(file);
        if (p == null || p.equals(loadingPath)) return null;
        loadingPath = p;
        Thread t = new Thread(() -> {
            try {
                SchematicInfo r = SchematicInfo.load(p);
                if (p.equals(loadingPath)) info = r;
            } catch (IOException | RuntimeException e) {
                if (p.equals(loadingPath)) info = null;
            }
        }, "kuro-schematic-mats");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** Called by the picker screen when a file is clicked. */
    public void chooseFile(String name) { setFile(name); }

    /** Opens the schematics folder in the OS file explorer, creating it first if it does not exist. */
    public static void openFolder() {
        Path dir = schematicsDir();
        try { Files.createDirectories(dir); } catch (IOException e) { say("Could not create " + dir); return; }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[]{"explorer.exe", dir.toString()}
                : os.contains("mac") ? new String[]{"open", dir.toString()}
                : new String[]{"xdg-open", dir.toString()};
        try {
            new ProcessBuilder(cmd).start();
            say("Opened " + dir);
        } catch (IOException | RuntimeException e) {
            say("Could not open the folder automatically. It is: " + dir);
        }
    }

    /** All schematic files under the folder (3 levels deep), sorted, as paths relative to it. */
    public static List<String> listSchematics() {
        Path dir = schematicsDir();
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) return names;
        try (Stream<Path> s = Files.walk(dir, 3)) {
            s.filter(Files::isRegularFile).forEach(f -> {
                String n = dir.relativize(f).toString().replace('\\', '/');
                String low = n.toLowerCase(Locale.ROOT);
                for (String ext : EXTENSIONS) if (low.endsWith(ext)) { names.add(n); break; }
            });
        } catch (IOException ignored) { }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static Path schematicsDir() { return FabricLoader.getInstance().getGameDir().resolve("schematics"); }

    private static Path resolveFile(String name) {
        if (name == null) return null;
        Path dir = schematicsDir();
        Path exact = dir.resolve(name);
        if (Files.isRegularFile(exact)) return exact;
        for (String ext : EXTENSIONS) {
            Path p = dir.resolve(name + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private void setFile(String name) {
        file = name.trim();
        info = null;
        save();
        Path p = resolveFile(file);
        if (p == null) { say("Saved \"" + file + "\", but it is not in " + schematicsDir() + " yet (see .build list)."); return; }
        say("Schematic set to " + p.getFileName() + ".");
        if (schematicsDir().relativize(p).toString().matches(".*\\s.*"))
            say("Warning: that name has a space in it. Rename the file (e.g. my_house.schem) or Baritone's #build cannot load it.");
        loadInfoAsync(p);
    }

    private volatile Path loadingPath;

    private void loadLayoutAsync(Path path) {
        layoutLoaded = null;
        layoutPath = path;
        Thread t = new Thread(() -> {
            try {
                dev.kuro.client.util.SchematicLayout l = dev.kuro.client.util.SchematicLayout.load(path);
                if (path.equals(layoutPath)) layoutLoaded = l;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                mc.execute(() -> say("Progress display unavailable (" + e.getMessage() + "). Building still works."));
            }
        }, "kuro-schematic-layout");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Baritone cannot place the first half of a double chest as "left"/"right" (it comes out single until the 2nd half joins it),
     * so it gets stuck. Ignoring the block property "type" fixes that, but slabs use "type" too (top / bottom / double), so it
     * is only switched on when the schematic has chests and no slabs.
     */
    // Per layer: does it hold chests, and does it hold other blocks that use the "type" / "facing" property
    // (slabs, pistons / stairs, doors, furnaces, ladders, buttons ...)? Ignoring a property helps chests but would
    // also blind Baritone for those blocks, so it is only switched on for layers where nothing else uses it.
    private boolean[] layerChest, layerTypeUsed, layerFacingUsed;
    private boolean chestFixLayered, ignoreTypeOn, ignoreFacingOn;

    private void applyChestFix(dev.kuro.client.util.SchematicLayout l) {
        chestFixLayered = false; ignoreTypeOn = false; ignoreFacingOn = false;
        if (!doubleChests.value && !chestFacing.value) return;
        layerChest = new boolean[l.sy];
        layerTypeUsed = new boolean[l.sy];
        layerFacingUsed = new boolean[l.sy];
        boolean chests = false;
        int per = l.sx * l.sz;
        for (int i = 0; i < l.ids.length; i++) {
            Block b = l.blocks[l.ids[i]];
            if (b == null) continue;
            int y = i / per;
            if (b instanceof ChestBlock) { layerChest[y] = true; chests = true; continue; }
            if (b instanceof net.minecraft.world.level.block.SlabBlock || b instanceof net.minecraft.world.level.block.piston.PistonBaseBlock) layerTypeUsed[y] = true;
            var st = b.defaultBlockState();
            if (st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    || st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) layerFacingUsed[y] = true;
        }
        if (!chests) return;
        chestFixLayered = true;
        say("Schematic has chests: Baritone will accept any chest side" + (chestFacing.value ? " and facing" : "")
                + " on layers where that is safe (no slabs / stairs / doors etc. in the same layer).");
    }

    private void setChestIgnore(boolean type, boolean facing) { applyIgnoreProps(type, facing); }

    /**
     * Writes Baritone's buildIgnoreProperties: "open" while door help is on (a door / gate you opened is still the right
     * block, so Baritone must not break and re-place it), plus "type" / "facing" for the chest fixes.
     */
    private void applyIgnoreProps(boolean type, boolean facing) {
        ignoreTypeOn = type; ignoreFacingOn = facing;
        List<String> want = new ArrayList<>();
        if (openDoors.value) want.add("open");
        if (type) want.add("type");
        if (facing) want.add("facing");
        boolean ok = true;
        if (apiOk) ok = BaritoneBridge.setListEntries("buildIgnoreProperties", want);   // your own entries are kept
        else {
            bcmd("set reset buildIgnoreProperties");
            if (!want.isEmpty()) ok = cmdSet("buildIgnoreProperties", String.join(",", want));
        }
        if (!ok && !want.isEmpty()) say("Could not set buildIgnoreProperties in this Baritone: doors / chests may get stuck.");
    }

    /** Switches the chest fixes on/off to match the layer Baritone is working on. */
    private void updateChestFix() {
        if (!chestFixLayered || progress == null || layerChest == null) return;
        int L = progress.lowestWrongLayer();
        if (L < 0 || L >= layerChest.length) return;
        boolean chestLayer = layerChest[L];
        boolean type = chestLayer && doubleChests.value && !layerTypeUsed[L];
        boolean facing = chestLayer && chestFacing.value && !layerFacingUsed[L];
        if (chestLayer && progressTick % 200 == 0 && ((doubleChests.value && layerTypeUsed[L]) || (chestFacing.value && layerFacingUsed[L])))
            say("Layer " + (L + 1) + " mixes chests with slabs / stairs / doors etc.: chests there may need a hand.");
        if (type != ignoreTypeOn || facing != ignoreFacingOn) setChestIgnore(type, facing);
    }

    /** Keeps the progress numbers and the "next blocks" list fresh. Cheap: a slice of the schematic per tick. */
    private void tickProgress() {
        if (progress == null) {
            dev.kuro.client.util.SchematicLayout l = layoutLoaded;
            if (l == null || origin == null) return;
            progress = new dev.kuro.client.util.BuildProgress(l, origin);
            applyChestFix(l);
        }
        progress.tick(mc);
        if (progressTick % 20 == 0) updateChestFix();
        if (progressTick % 10 == 5) {
            ghostCount = showGhost.value && mc.player != null
                    ? progress.missingNear(ghostIdx, mc.player.getX(), mc.player.getY(), mc.player.getZ(), ghostRange.intValue()) : 0;
        }
        if (++progressTick % 10 == 0 && showNext.value && mc.player != null) {
            nextBlocks = progress.next(10, mc.player.getX(), mc.player.getY(), mc.player.getZ(), layers.value);
        }
    }

    private void loadInfoAsync(Path path) {
        info = null;
        loadingPath = path;
        Thread t = new Thread(() -> {
            try {
                SchematicInfo si = SchematicInfo.load(path);
                if (!path.equals(loadingPath)) return;                // another file was chosen meanwhile
                info = si;
                mc.execute(() -> say("Needs " + si.totalBlocks + " blocks of " + si.items.size() + " kinds. The refill takes only those."));
            } catch (IOException | RuntimeException e) {
                mc.execute(() -> say("Could not read the materials (" + e.getMessage() + "). The refill will take any placeable block."));
            }
        }, "kuro-schematic");
        t.setDaemon(true);
        t.start();
    }

    private void listFiles() {
        Path dir = schematicsDir();
        if (!Files.isDirectory(dir)) { say("No schematics folder yet. Create " + dir + " and put your files there."); return; }
        try (Stream<Path> s = Files.walk(dir, 3)) {
            List<String> names = new ArrayList<>();
            s.filter(Files::isRegularFile).forEach(f -> {
                String n = dir.relativize(f).toString().replace('\\', '/');
                String low = n.toLowerCase(Locale.ROOT);
                for (String ext : EXTENSIONS) if (low.endsWith(ext)) { names.add(n); break; }
            });
            if (names.isEmpty()) { say("No .schem / .litematic / .schematic files in " + dir); return; }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            say("Schematics in " + dir + ":");
            for (int i = 0; i < Math.min(names.size(), 25); i++) say("  " + names.get(i));
            if (names.size() > 25) say("  ... and " + (names.size() - 25) + " more");
        } catch (IOException e) {
            say("Could not read " + dir);
        }
    }

    private void showMaterials() {
        SchematicInfo si = info;
        if (si == null) {
            Path p = resolveFile(file);
            if (p == null) { say("Choose a schematic first."); return; }
            say("Reading " + p.getFileName() + " ... run this again in a moment.");
            loadInfoAsync(p);
            return;
        }
        say(si.fileName + ": " + si.totalBlocks + " blocks.");
        int n = 0;
        for (Map.Entry<Item, Integer> e : si.items.entrySet()) {
            if (n++ >= 12) { say("  ... and " + (si.items.size() - 12) + " more kinds"); break; }
            say("  " + BuiltInRegistries.ITEM.getKey(e.getKey()).getPath() + " x" + e.getValue());
        }
    }

    private void status() {
        say("Schematic: " + (file == null ? "(none)" : file));
        say("Origin: " + (origin == null ? "(not set)" : fmt(origin)) + "   Refill chest: " + (chest == null ? "(not set)" : fmt(chest)));
        say("State: " + (isEnabled() ? state.name().toLowerCase(Locale.ROOT).replace('_', ' ') : "off")
                + (BaritoneBridge.installed() ? "" : "   (Baritone is NOT installed)"));
    }

    // ================================================================== chat command  (.build ...)

    public void command(String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "folder", "open" -> openFolder();
            case "mats", "needs" -> mc.execute(() -> mc.setScreen(new dev.kuro.client.gui.MaterialsScreen(mc.screen, this)));
            case "gui", "choose", "pick-file" -> mc.execute(() -> mc.setScreen(new dev.kuro.client.gui.SchematicPickerScreen(mc.screen, this)));
            case "file", "schematic" -> {
                if (a.length < 2) say("Usage: .build file <name>   (a file in the schematics folder, see .build list)");
                else setFile(String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
            }
            case "origin" -> {
                BlockPos p = a.length >= 4 ? parsePos(a[1], a[2], a[3]) : null;
                if (a.length >= 4 && p == null) say("Usage: .build origin [x y z]");
                else if (p != null) { origin = p; save(); say("Build origin set to " + fmt(origin) + "."); }
                else setOriginHere();
            }
            case "chest" -> {
                BlockPos p = a.length >= 4 ? parsePos(a[1], a[2], a[3]) : null;
                if (a.length >= 4 && p == null) say("Usage: .build chest [x y z]   (no numbers = the block you look at)");
                else if (p != null) setChest(p);
                else pickLooked();
            }
            case "pick" -> armPicker();
            case "list", "ls" -> listFiles();
            case "info", "materials" -> showMaterials();
            case "status" -> status();
            case "start", "on" -> { if (isEnabled()) say("Already running."); else setEnabled(true); }
            case "stop", "off" -> { if (isEnabled()) setEnabled(false); else say("Not running."); }
            default -> {
                say(".build gui            list schematics and click one  |  .build folder  opens the folder");
                say(".build file <name>    choose the schematic (in .minecraft/schematics)");
                say(".build origin [x y z] where the schematic's corner goes (default: where you stand)");
                say(".build chest [x y z]  refill chest (default: the block you look at); .build pick = right-click it");
                say(".build mats           materials menu with tick boxes");
                say(".build list | info | status | start | stop");
            }
        }
    }

    private static BlockPos parsePos(String x, String y, String z) {
        try { return new BlockPos(Integer.parseInt(x), Integer.parseInt(y), Integer.parseInt(z)); }
        catch (NumberFormatException e) { return null; }
    }

    // ================================================================== persistence

    private void save() {
        try {
            Files.writeString(CONFIG, "file=" + (file == null ? "" : file) + "\norigin=" + raw(origin) + "\nchest=" + raw(chest) + "\n");
        } catch (IOException ignored) {}
    }

    private void load() {
        try {
            if (!Files.exists(CONFIG)) return;
            for (String line : Files.readAllLines(CONFIG)) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq), value = line.substring(eq + 1).trim();
                switch (key) {
                    case "file" -> file = value.isEmpty() ? null : value;
                    case "origin" -> origin = parseRaw(value);
                    case "chest" -> chest = parseRaw(value);
                    default -> {}
                }
            }
        } catch (IOException ignored) {}
    }

    private static String raw(BlockPos p) { return p == null ? "" : p.getX() + " " + p.getY() + " " + p.getZ(); }

    private static BlockPos parseRaw(String v) {
        String[] t = v.split("\\s+");
        return t.length == 3 ? parsePos(t[0], t[1], t[2]) : null;
    }

    private static String fmt(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }

    private static void say(String s) { CommandManager.say(s); }

    // ================================================================== overlay

    @Override
    public void onWorld() {
        if (state != S.IDLE) {
            dev.kuro.client.util.BuildProgress bp = progress;
            if (bp != null && showOutline.value) {
                BlockPos o = bp.origin();
                double x2 = o.getX() + bp.layout.sx, y2 = o.getY() + bp.layout.sy, z2 = o.getZ() + bp.layout.sz;
                // light corner brackets instead of a heavy glowing wireframe, plus a faint footprint on the ground
                WorldGfx.cornerBox(o.getX(), o.getY(), o.getZ(), x2, y2, z2, 0xE6FFD27F, 0x00FFD27F, 2, 2.0);
                WorldGfx.square(o.getX(), o.getZ(), x2, z2, o.getY() + 0.01, 0x70FFD27F, 0x0AFFD27F, 1);
            }
            if (bp != null && showGhost.value && ghostCount > 0) {
                // every block still missing, as a faint tinted ghost (opacity setting; 20% = 80% see-through)
                int alpha = Mth.clamp(Math.round(ghostOpacity.intValue() * 2.55f), 0, 255);
                for (int k = 0; k < ghostCount; k++) {
                    int i = ghostIdx[k];
                    net.minecraft.world.level.block.Block blk = bp.blockAtIndex(i);
                    if (blk == null) continue;
                    BlockPos b = bp.blockPos(i);
                    int rgb = blk.defaultMapColor().col;
                    WorldGfx.ghostBlock(b.getX(), b.getY(), b.getZ(), b.getX() + 1, b.getY() + 1, b.getZ() + 1, rgb, alpha, bp.exposedFaces(i));
                }
            }
            if (bp != null && showNext.value) {
                // the next block stands out (green, glowing); the queue behind it fades out with distance in the order
                for (int i = nextBlocks.size() - 1; i >= 0; i--) {
                    BlockPos b = nextBlocks.get(i);
                    double e = 0.004;                              // a hair inside the block so edges don't flicker against neighbours
                    if (i == 0) {
                        WorldGfx.box(b.getX() - e, b.getY() - e, b.getZ() - e, b.getX() + 1 + e, b.getY() + 1 + e, b.getZ() + 1 + e,
                                0xFF7FFFA0, 0x387FFFA0, 2, true);
                    } else {
                        float f = 1f - (i - 1) / (float) Math.max(1, nextBlocks.size() - 1);          // 1 .. 0
                        int la = 0x38 + (int) (0x50 * f), fa = 0x06 + (int) (0x0C * f);
                        WorldGfx.box(b.getX() + 0.03, b.getY() + 0.03, b.getZ() + 0.03, b.getX() + 0.97, b.getY() + 0.97, b.getZ() + 0.97,
                                (la << 24) | 0xFFFFFF, (fa << 24) | 0xFFFFFF, 1, false);
                    }
                }
            }
        }
        if (!esp.value) return;
        if (chest != null) {
            WorldGfx.cornerBox(chest.getX(), chest.getY(), chest.getZ(), chest.getX() + 1, chest.getY() + 1, chest.getZ() + 1,
                    0xFF7FD4FF, 0x187FD4FF, 2, 0.3);
        }
        if (origin != null) {
            WorldGfx.cornerBox(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 1, origin.getY() + 1, origin.getZ() + 1,
                    0xFF9CFFB0, 0x189CFFB0, 2, 0.3);
        }
    }

    /** Status line + progress readout + bar at the top of the screen; drag it in the HUD layout editor. */
    private final HudPanel statusPanel = new HudPanel(this, "Build status");

    @Override
    public java.util.List<HudElement> hudElements() { return java.util.List.of(statusPanel); }

    @Override
    public void onHud(GuiGraphics g) {
        boolean demo = state == S.IDLE && dev.kuro.client.gui.HudEditScreen.editing();   // layout editor: stand-in text so the panel can be moved
        if (mc.player == null || (state == S.IDLE && !demo)) return;
        String text = demo ? "Auto Build: building" : switch (state) {
            case BUILDING -> "Auto Build: building";
            case TO_CHEST -> "Auto Build: walking to the refill chest";
            case OPENING -> "Auto Build: opening the chest";
            case LOOTING -> "Auto Build: restocking";
            case LOOK_BACK -> "Auto Build: back to work";
            case DOOR -> "Auto Build: opening a door";
            default -> "";
        };
        String line = null;
        double frac = 0;
        dev.kuro.client.util.BuildProgress bp = progress;
        if (demo) {
            line = "42%   126 / 300 blocks   layer 2 / 6";
            frac = 0.42;
        } else if (showProgress.value && bp != null && bp.total() > 0) {
            int pct = (int) Math.floor(bp.fraction() * 100.0);
            int low = bp.lowestWrongLayer();
            line = pct + "%   " + bp.done() + " / " + bp.total() + " blocks"
                    + (low >= 0 ? "   layer " + (low + 1) + " / " + bp.layerCount() : "   done");
            frac = bp.fraction();
        }

        int bw = 140;
        int w = Math.max(bw, Math.max(text.isEmpty() ? 0 : dev.kuro.client.util.SdfText.w(text) + 14,
                line == null ? 0 : dev.kuro.client.util.SdfText.w(line) + 14));
        statusPanel.place((dev.kuro.client.util.Projector.width() - w) / 2, 6, w, line != null ? 32 : 13);
        int cx = statusPanel.x() + w / 2, top = statusPanel.y();
        if (!text.isEmpty()) Look.tag(g, mc.font, text, cx, top, Look.ACCENT);
        if (line != null) {
            Look.tag(g, mc.font, line, cx, top + 14, Look.ACCENT);
            int x0 = cx - bw / 2, y0 = top + 26;
            g.fill(x0 - 1, y0 - 1, x0 + bw + 1, y0 + 5, 0xB0000000);
            g.fill(x0, y0, x0 + (int) (bw * frac), y0 + 4, 0xFF7FFFA0);
        }
    }
}
