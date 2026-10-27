package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import dev.kuro.client.util.ForcedKeys;
import dev.kuro.client.util.SwapLimit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tunnels forward in the direction you were facing when you switched it on by HOLDING the break key (right click by
 * default), walking forward as the tunnel opens.
 *
 * Cave safety: before every step it checks a box around the tunnel ahead. Any air, water or lava next to / above /
 * below the tunnel that the miner did not dig itself counts as a cave: it lets go of every key and stops (or switches
 * itself off).
 *
 * Pickaxe upkeep: when no usable pickaxe is left it runs "/ah <search>", reads the price out of each pickaxe's lore,
 * clicks the cheapest one, presses the confirm button and goes back to mining.
 */
public class AutoMiner extends Module {
    private String search = "diamond pickaxe";

    // ---- mining ----
    private final Setting.Bool rightClick = add(new Setting.Bool("Break with right click", true));   // off = left click
    private final Setting.Bool walk = add(new Setting.Bool("Walk forward", true));
    private final Setting.Bool aim = add(new Setting.Bool("Aim at block", true));
    // ---- cave safety ----
    private final Setting.Bool caveCheck = add(new Setting.Bool("Cave detection", true));
    private final Setting.Num lookAhead = add(new Setting.Num("Look ahead (blocks)", 3, 1, 6, 1));
    private final Setting.Num margin = add(new Setting.Num("Cave margin (blocks)", 1, 1, 3, 1));
    private final Setting.Bool disableOnCave = add(new Setting.Bool("Switch off on cave", false));
    // ---- pickaxe upkeep ----
    private final Setting.Bool autoBuy = add(new Setting.Bool("Auto buy pickaxe (/ah)", true));
    private final Setting.Num minDurability = add(new Setting.Num("Replace at durability", 3, 0, 50, 1));
    private final Setting.Num clickDelay = add(new Setting.Num("GUI delay (ticks)", 10, 3, 40, 1));
    private final Setting.Num maxTries = add(new Setting.Num("Buy attempts", 3, 1, 10, 1));
    private final Setting.Text searchBox = add(new Setting.Text("AH search (type + Enter)", t -> { search = t.trim(); say("AH search set to: " + search, ChatFormatting.GREEN); }));

    private enum State { MINE, SEND, WAIT_AH, WAIT_CONFIRM, VERIFY }
    private State state = State.MINE;
    private int timer, attempts, screenAge, lastContainer = -1, ahContainer = -1;
    private Direction dir;
    private final Set<Long> dug = new HashSet<>();
    private final Set<Long> seenSolid = new HashSet<>();
    private boolean cavePaused;

    private static final Pattern PRICE = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbBtT])?");

    public AutoMiner() { super("Auto Miner", Category.PLAYER); }

    @Override
    protected void onEnable() {
        if (mc.player == null) { setEnabled(false); return; }
        dir = mc.player.getDirection();
        dug.clear(); seenSolid.clear();
        state = State.MINE; attempts = 0; timer = 0; cavePaused = false;
        say("Mining " + dir.getName() + ". Right click = " + (rightClick.value ? "break" : "off (left click)") + ".", ChatFormatting.GRAY);
    }

    @Override protected void onDisable() { releaseKeys(); }

    private void say(String m, ChatFormatting c) {
        mc.execute(() -> { if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Auto Miner] " + m).withStyle(c), false); });
    }

    private KeyMapping breakKey() { return rightClick.value ? mc.options.keyUse : mc.options.keyAttack; }

    private void releaseKeys() {
        if (mc.options == null) return;
        ForcedKeys.release(mc.options.keyUse);
        ForcedKeys.release(mc.options.keyAttack);
        ForcedKeys.release(mc.options.keyUp);
        KeyMapping.setAll();
    }

    private void fail(String why) {
        say(why, ChatFormatting.RED);
        if (mc.screen instanceof AbstractContainerScreen<?> && mc.player != null) mc.player.closeContainer();
        setEnabled(false);
    }

    // ===================================================================== tick

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || dir == null) { releaseKeys(); return; }
        if (mc.player.isSpectator() || AutoEat.eating()) { releaseKeys(); return; }

        if (state != State.MINE) { buyTick(); return; }

        if (mc.screen != null) { releaseKeys(); return; }          // some other menu is open: wait

        // ---- pickaxe ----
        int toolState = ensurePickaxe();
        if (toolState == 0) {                                       // none usable
            releaseKeys();
            if (!autoBuy.value) { fail("No usable pickaxe left and Auto buy is off."); return; }
            state = State.SEND; timer = 0; attempts = 0;
            say("Pickaxe is gone, buying one from /ah...", ChatFormatting.YELLOW);
            return;
        }
        if (toolState == 2) { releaseKeys(); return; }              // swap refused this tick, retry

        // ---- cave safety ----
        BlockPos origin = mc.player.blockPosition();
        trackDug(origin);
        if (caveCheck.value) {
            String hazard = findHazard(origin);
            if (hazard != null) {
                releaseKeys();
                if (!cavePaused) {
                    cavePaused = true;
                    say("Stopped: " + hazard, ChatFormatting.RED);
                }
                if (disableOnCave.value) setEnabled(false);
                return;
            }
            cavePaused = false;
        }

        // ---- aim + hold keys ----
        BlockPos feet1 = origin.relative(dir, 1), head1 = feet1.above();
        boolean clear1 = !solid(feet1) && !solid(head1);
        if (aim.value) aimAt(origin);

        ForcedKeys.force(breakKey());
        if (rightClick.value) ForcedKeys.release(mc.options.keyAttack); else ForcedKeys.release(mc.options.keyUse);
        if (walk.value && clear1) ForcedKeys.force(mc.options.keyUp); else ForcedKeys.release(mc.options.keyUp);
        if (dug.size() > 4000) { dug.clear(); seenSolid.clear(); }
    }

    // ===================================================================== mining helpers

    private boolean solid(BlockPos p) {
        BlockState st = mc.level.getBlockState(p);
        return st.getFluidState().isEmpty() && !st.getCollisionShape(mc.level, p).isEmpty();
    }

    /** Remembers which tunnel blocks we broke ourselves, so our own tunnel never looks like a cave. */
    private void trackDug(BlockPos origin) {
        for (int f = 1; f <= 2; f++) for (int v = 0; v <= 1; v++) {
            BlockPos p = origin.relative(dir, f).above(v);
            long k = p.asLong();
            if (solid(p)) seenSolid.add(k);
            else if (seenSolid.contains(k)) dug.add(k);
        }
    }

    /** Returns a description of the first open space found around the tunnel ahead, or null when it is all rock. */
    private String findHazard(BlockPos origin) {
        int L = lookAhead.intValue(), M = margin.intValue();
        Direction side = dir.getClockWise();
        for (int f = 0; f <= L + 1; f++) {
            for (int lat = -M; lat <= M; lat++) {
                for (int v = -M; v <= 1 + M; v++) {
                    boolean tunnel = lat == 0 && (v == 0 || v == 1);
                    if (tunnel && f == 0) continue;                   // where we stand
                    BlockPos p = origin.relative(dir, f).relative(side, lat).above(v);
                    if (solid(p) || dug.contains(p.asLong())) continue;
                    BlockState st = mc.level.getBlockState(p);
                    String what = !st.getFluidState().isEmpty() ? "liquid" : "open air / cave";
                    return what + " " + (tunnel ? "ahead in the tunnel" : "next to the tunnel") + " at "
                            + p.getX() + ", " + p.getY() + ", " + p.getZ();
                }
            }
        }
        return null;
    }

    private void aimAt(BlockPos origin) {
        BlockPos[] order = {
                origin.relative(dir, 1), origin.relative(dir, 1).above(),
                origin.relative(dir, 2), origin.relative(dir, 2).above() };
        BlockPos t = order[0];
        for (BlockPos p : order) if (solid(p)) { t = p; break; }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 c = Vec3.atCenterOf(t);
        double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
        mc.player.setYRot((float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90f);
        mc.player.setXRot((float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI));
    }

    // ===================================================================== pickaxe handling

    private boolean usablePickaxe(ItemStack st) {
        if (st.isEmpty() || !st.is(ItemTags.PICKAXES)) return false;
        if (!st.isDamageableItem()) return true;
        return st.getMaxDamage() - st.getDamageValue() > minDurability.intValue();
    }

    /** 1 = a usable pickaxe is in hand, 0 = none anywhere, 2 = working on it (swap pending). */
    private int ensurePickaxe() {
        var inv = mc.player.getInventory();
        if (usablePickaxe(inv.getItem(inv.getSelectedSlot()))) return 1;
        for (int i = 0; i < 9; i++) {
            if (usablePickaxe(inv.getItem(i))) return SwapLimit.select(i) ? 1 : 2;
        }
        for (int i = 9; i < 36; i++) {
            if (usablePickaxe(inv.getItem(i))) {
                if (!SwapLimit.take()) return 2;
                mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, i, inv.getSelectedSlot(), ClickType.SWAP, mc.player);
                return 2;
            }
        }
        return 0;
    }

    private boolean haveUsablePickaxe() {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) if (usablePickaxe(inv.getItem(i))) return true;
        return false;
    }

    // ===================================================================== /ah buying

    private void buyTick() {
        timer++;
        AbstractContainerMenu menu = mc.screen instanceof AbstractContainerScreen<?> s ? s.getMenu() : null;
        if (menu != null && menu.containerId != lastContainer) { lastContainer = menu.containerId; screenAge = 0; }
        if (menu != null) screenAge++;

        switch (state) {
            case SEND -> {
                if (mc.screen != null) { mc.player.closeContainer(); return; }
                if (timer < 5) return;
                mc.player.connection.sendCommand("ah " + search);
                state = State.WAIT_AH; timer = 0; ahContainer = -1;
            }
            case WAIT_AH -> {
                if (menu == null || menu.slots.size() <= 36) {
                    if (timer > 100) retryOrFail("The /ah menu never opened.");
                    return;
                }
                if (ahContainer == -1) ahContainer = menu.containerId;
                if (screenAge < clickDelay.intValue()) return;       // let the server fill the menu
                int size = menu.slots.size() - 36, best = -1;
                double bestPrice = Double.MAX_VALUE;
                boolean sawPickaxe = false;
                for (int i = 0; i < size; i++) {
                    ItemStack st = menu.getSlot(i).getItem();
                    if (st.isEmpty() || !st.is(ItemTags.PICKAXES)) continue;
                    sawPickaxe = true;
                    double price = priceOf(st);
                    if (price >= 0 && price < bestPrice) { bestPrice = price; best = i; }
                }
                if (best < 0) {
                    if (timer > 100) retryOrFail(sawPickaxe ? "Found pickaxes but could not read a price from their lore." : "No pickaxes listed for \"" + search + "\".");
                    return;
                }
                mc.gameMode.handleInventoryMouseClick(menu.containerId, best, 0, ClickType.PICKUP, mc.player);
                say("Clicked the cheapest pickaxe (" + fmt(bestPrice) + ").", ChatFormatting.GRAY);
                state = State.WAIT_CONFIRM; timer = 0;
            }
            case WAIT_CONFIRM -> {
                if (menu != null && menu.containerId != ahContainer && menu.slots.size() > 36) {
                    if (screenAge < Math.max(3, clickDelay.intValue() / 2)) return;
                    int size = menu.slots.size() - 36, pick = -1;
                    for (int i = 0; i < size && pick < 0; i++) if (isConfirm(menu.getSlot(i).getItem())) pick = i;
                    if (pick >= 0) {
                        mc.gameMode.handleInventoryMouseClick(menu.containerId, pick, 0, ClickType.PICKUP, mc.player);
                        state = State.VERIFY; timer = 0;
                    } else if (timer > 60) retryOrFail("Could not find the confirm button in the purchase menu.");
                    return;
                }
                if (timer > 30) { state = State.VERIFY; timer = 0; }   // no confirm menu showed up: maybe it bought instantly
            }
            case VERIFY -> {
                if (haveUsablePickaxe()) {
                    if (mc.screen != null) mc.player.closeContainer();
                    say("Bought a pickaxe. Back to mining.", ChatFormatting.GREEN);
                    state = State.MINE; timer = 0; cavePaused = false;
                    return;
                }
                if (timer > 50) retryOrFail("Clicked buy but no pickaxe arrived (not enough money, or someone sniped it?).");
            }
            default -> { }
        }
    }

    private void retryOrFail(String why) {
        attempts++;
        if (attempts >= maxTries.intValue()) { fail(why + " Giving up."); return; }
        say(why + " Retrying (" + attempts + "/" + maxTries.intValue() + ").", ChatFormatting.YELLOW);
        if (mc.screen != null) mc.player.closeContainer();
        state = State.SEND; timer = 0;
    }

    private boolean isConfirm(ItemStack st) {
        if (st.isEmpty()) return false;
        String name = st.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("confirm") || name.contains("accept") || name.equals("buy")) return true;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        return id.startsWith("lime_") || id.startsWith("green_") || id.equals("emerald_block");
    }

    /** Price parsed from the item's lore ("$1,500", "$2.5K", "$1M"...), -1 when none found. Lines that say "price" win. */
    private double priceOf(ItemStack st) {
        ItemLore lore = st.get(DataComponents.LORE);
        if (lore == null) return -1;
        double any = -1;
        for (Component line : lore.lines()) {
            String s = line.getString();
            Matcher m = PRICE.matcher(s);
            if (!m.find()) continue;
            double v;
            try { v = Double.parseDouble(m.group(1).replace(",", "")); } catch (NumberFormatException e) { continue; }
            String suf = m.group(2);
            if (suf != null) switch (Character.toLowerCase(suf.charAt(0))) {
                case 'k' -> v *= 1_000d;
                case 'm' -> v *= 1_000_000d;
                case 'b' -> v *= 1_000_000_000d;
                case 't' -> v *= 1_000_000_000_000d;
                default -> { }
            }
            if (s.toLowerCase(java.util.Locale.ROOT).contains("price")) return v;
            if (any < 0) any = v;
        }
        return any;
    }

    private static String fmt(double v) { return v >= 1000 ? String.format("$%,.0f", v) : "$" + (long) v; }
}
