package dev.kuro.client.module.impl.automace;

import dev.kuro.client.module.impl.AutoMace;
import dev.kuro.client.util.BlockPlacer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ShieldBreakHandler {

    private static final Minecraft mc = Minecraft.getInstance();

    private final AutoMace parent;
    private int slamState = 0;
    private long slamTimer = 0L;

    public ShieldBreakHandler(AutoMace parent) {
        this.parent = parent;
    }

    public int getSlamState() { return slamState; }
    public void setSlamState(int state) { this.slamState = state; }
    public void reset() { this.slamState = 0; this.slamTimer = 0L; }

    private static final long SLAM_TIMEOUT_MS = 1500L;

    /**
     * Shield handling, one step per tick the way a player would do it: take the axe (one hotbar swap per tick), hit
     * once the attack is charged and the crosshair is on the target, then take the mace and hit again. Anything that
     * cannot be done this tick (swap refused, attack not charged, not aimed) just waits for the next one; nothing is
     * sent early. Returns true while this handler owns the tick.
     */
    public boolean handleShieldBreak(Entity target, double fallDist) {
        if (mc.gameMode == null || mc.player == null) return false;

        boolean targetBlocking = target instanceof LivingEntity && ((LivingEntity) target).isBlocking();
        AutoMace.ShieldMode mode = parent.getShieldMode();

        if (slamState == 1 && System.currentTimeMillis() - slamTimer > SLAM_TIMEOUT_MS) slamState = 0;   // never stay stuck mid-combo

        if ((targetBlocking || slamState > 0) && mode == AutoMace.ShieldMode.STUN_SLAM) {
            int axeSlot = findAxeSlot();
            if (axeSlot != -1) {
                if (slamState == 0) {
                    if (parent.getSavedSlot() == -1) parent.setSavedSlot(BlockPlacer.selected());
                    if (!BlockPlacer.select(axeSlot)) return true;       // swap refused this tick
                    if (!parent.executeAttack(target)) return true;      // not charged / not aimed yet: keep the axe, wait

                    slamState = 1;
                    slamTimer = System.currentTimeMillis();
                    if (parent.getFastStunSlam().value) switchToAppropriateMace(fallDist);   // straight after the hit, if the swap is free
                    return true;
                } else if (slamState == 1) {
                    if (!hasMace()) {
                        // Fast Stun Slam: the swap after the axe hit may have been refused, retry. Otherwise the mace comes out after the delay.
                        boolean due = System.currentTimeMillis() - slamTimer >= Math.max(50, (long) parent.getStunSlamDelay().value);
                        if (parent.getFastStunSlam().value || due) switchToAppropriateMace(fallDist);
                        return true;
                    }
                    if (System.currentTimeMillis() - slamTimer >= Math.max(50, (long) parent.getStunSlamDelay().value)
                            && parent.executeAttack(target)) {
                        slamState = 2;
                        parent.registerMaceHit();
                    }
                    return true;
                } else if (slamState == 2) {
                    return true;
                }
            } else {
                slamState = 0;   // no axe in the hotbar: this mode cannot run, fall through to a normal mace hit
            }
        }

        if (targetBlocking && mode == AutoMace.ShieldMode.DRAIN) {
            if (parent.getSavedSlot() == -1) parent.setSavedSlot(BlockPlacer.selected());
            if (!hasMace() && !switchToAppropriateMace(fallDist)) return true;
            if (hasMace() && parent.executeAttack(target)) parent.registerMaceHit();   // one swing per charge, like anyone clicking
            return true;
        }

        return false;
    }

    /** Called when there is no target any more: a combo that never finished must not stay armed. */
    public void abortStaleSlam() {
        if (slamState == 1) slamState = 0;
    }

    public int findAxeSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    /** Takes the right mace. True when it is in hand afterwards, false when no mace exists or the swap was refused (try next tick). */
    public boolean switchToAppropriateMace(double fallDistance) {
        boolean useDensity = fallDistance >= parent.getDensityThreshold().value;
        int primarySlot = useDensity ? findEnchantedMaceSlot("density") : findEnchantedMaceSlot("breach");
        int secondarySlot = useDensity ? findEnchantedMaceSlot("breach") : findEnchantedMaceSlot("density");

        int targetSlot = primarySlot != -1 ? primarySlot : (secondarySlot != -1 ? secondarySlot : findAnyMaceSlot());

        return targetSlot != -1 && BlockPlacer.select(targetSlot);
    }

    public int findEnchantedMaceSlot(String enchantKey) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.MACE) {
                var enchants = stack.getEnchantments();
                if (enchants != null) {
                    String str = enchants.toString().toLowerCase(java.util.Locale.ROOT);
                    if (str.contains(enchantKey.toLowerCase(java.util.Locale.ROOT))) {
                        return i;
                    }
                    for (var entry : enchants.entrySet()) {
                        String keyName = entry.getKey().getRegisteredName().toLowerCase(java.util.Locale.ROOT);
                        if (keyName.contains(enchantKey.toLowerCase(java.util.Locale.ROOT))) {
                            return i;
                        }
                    }
                }
            }
        }
        return -1;
    }

    public int findAnyMaceSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() == Items.MACE) {
                return i;
            }
        }
        return -1;
    }

    public boolean hasMace() {
        if (mc.player == null) return false;
        ItemStack held = mc.player.getInventory().getItem(BlockPlacer.selected());
        return !held.isEmpty() && held.is(Items.MACE);
    }
}