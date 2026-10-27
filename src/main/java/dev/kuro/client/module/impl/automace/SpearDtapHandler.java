package dev.kuro.client.module.impl.automace;

import dev.kuro.client.module.impl.AutoMace;
import dev.kuro.client.util.BlockPlacer;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;

public class SpearDtapHandler {

    private static final Minecraft mc = Minecraft.getInstance();
    private static final long DTAP_SELF_FEEDBACK_GUARD_MS = 300L;

    private final AutoMace parent;
    private final ShieldBreakHandler shieldBreakHandler;
    private final AutoMaceTargeting targeting;

    private boolean dtapArmed = false;
    private int dtapOriginalSlot = -1;
    private int dtapSwapBackTick = 0;
    private long dtapNoArmUntil = 0L;

    public SpearDtapHandler(AutoMace parent, ShieldBreakHandler shieldBreakHandler, AutoMaceTargeting targeting) {
        this.parent = parent;
        this.shieldBreakHandler = shieldBreakHandler;
        this.targeting = targeting;
    }

    public void onMotionPre() {
        if (dtapSwapBackTick > 0) {
            dtapSwapBackTick--;
            if (dtapSwapBackTick == 0 && dtapOriginalSlot != -1) {
                if (BlockPlacer.select(dtapOriginalSlot)) dtapOriginalSlot = -1;
                else dtapSwapBackTick = 1;   // swap refused this tick: keep trying until the old slot is back
            }
        }

        if (dtapArmed) {
            dtapArmed = false;
            executeDtapMace();
            return;
        }

        if (!parent.getSpearDtap().value) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (dtapSwapBackTick > 0) return;

        if (!parent.getRocketDiveHandler().idle() || parent.isMaceHit() || shieldBreakHandler.getSlamState() != 0) return;

        if (System.currentTimeMillis() < dtapNoArmUntil) return;
        
        // Kuro replacement for KineticHitEvent: 
        // If we are actively right-clicking a spear, arm the DTap.
        boolean holdsSpear = mc.player.getMainHandItem().is(ItemTags.SPEARS)
                || mc.player.getOffhandItem().is(ItemTags.SPEARS);
        
        if (!holdsSpear) return;
        
        // Ensure we are actually using the spear (right-click charging)
        if (!mc.player.isUsingItem()) return;

        dtapOriginalSlot = BlockPlacer.selected();
        dtapArmed = true;
    }

    private void executeDtapMace() {
        if (mc.player == null || mc.level == null || mc.gameMode == null || dtapOriginalSlot == -1) {
            dtapOriginalSlot = -1;
            return;
        }
        int maceSlot = findDtapMaceSlot();
        Entity target = maceSlot == -1 ? null : findDtapTarget();
        if (target == null) { dtapOriginalSlot = -1; return; }   // nothing to hit: forget the slot instead of leaving it stale

        if (!BlockPlacer.select(maceSlot)) { dtapOriginalSlot = -1; return; }   // no free swap this tick: skip this dtap, nothing was changed
        // the hit after a spear jab is a normal quick click: it does not wait for a full charge, but it still needs reach + aim
        if (!parent.executeAttack(target, false)) { dtapSwapBackTick = 1; return; }   // swap back next tick (swap budget)
        dtapNoArmUntil = System.currentTimeMillis() + DTAP_SELF_FEEDBACK_GUARD_MS;
        dtapSwapBackTick = 1;
    }

    private int findDtapMaceSlot() {
        boolean useDensity = mc.player.fallDistance >= parent.getDensityThreshold().value;
        int primary = useDensity ? shieldBreakHandler.findEnchantedMaceSlot("density") : shieldBreakHandler.findEnchantedMaceSlot("breach");
        int secondary = useDensity ? shieldBreakHandler.findEnchantedMaceSlot("breach") : shieldBreakHandler.findEnchantedMaceSlot("density");
        return primary != -1 ? primary : (secondary != -1 ? secondary : shieldBreakHandler.findAnyMaceSlot());
    }

    private Entity findDtapTarget() {
        Entity cross = mc.crosshairPickEntity;
        if (targeting.isValidTarget(cross)) return cross;
        return targeting.findOptimalTarget(parent.reach());
    }

    public void reset() {
        dtapArmed = false;
        dtapOriginalSlot = -1;
        dtapSwapBackTick = 0;
        dtapNoArmUntil = 0L;
    }

    public void onDisable() {
        if (dtapSwapBackTick > 0 && dtapOriginalSlot != -1 && mc.player != null) {
            BlockPlacer.selectForce(dtapOriginalSlot);
        }
        reset();
    }
}