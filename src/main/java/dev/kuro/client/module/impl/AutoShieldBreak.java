package dev.kuro.client.module.impl;

import dev.kuro.client.util.SwapLimit;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * When your crosshair is on a player who is blocking with a shield (and facing you), switches to an axe from
 * your hotbar and hits them so the shield is disabled, then switches back once they stop blocking.
 */
public class AutoShieldBreak extends Module {
    private final Setting.Bool autoAttack = add(new Setting.Bool("Auto attack", true));
    private final Setting.Bool switchBack = add(new Setting.Bool("Switch back", true));
    private final Setting.Num delay = add(new Setting.Num("Delay (ticks)", 0, 0, 10, 1));
    private int prevSlot = -1, wait;

    private static AutoShieldBreak instance;
    /** True while the module is switched on (Trigger Bot's shield check backs off then). */
    static boolean active() { return instance != null && instance.isEnabled(); }

    public AutoShieldBreak() { super("Auto Shield Break", Category.COMBAT); instance = this; }

    @Override protected void onEnable() { prevSlot = -1; wait = 0; }
    @Override protected void onDisable() { if (prevSlot >= 0 && switchBack.value && mc.player != null) SwapLimit.selectNow(prevSlot); prevSlot = -1; }

    @Override
    public void onTick() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.screen != null || Freecam.active() || mc.player.isSpectator()) { restore(); return; }

        Player target = null;
        if (mc.hitResult instanceof EntityHitResult hit && hit.getType() == HitResult.Type.ENTITY
                && hit.getEntity() instanceof Player p && p != mc.player && p.isAlive() && !p.isSpectator()
                && p.isBlocking() && facingUs(p)) target = p;

        if (target == null) { wait = 0; restore(); return; }

        var inv = mc.player.getInventory();
        if (!isAxe(inv.getItem(inv.getSelectedSlot()))) {
            int axe = -1;
            for (int i = 0; i < 9; i++) if (isAxe(inv.getItem(i))) { axe = i; break; }
            if (axe < 0) return;                                    // no axe in the hotbar
            int cur = inv.getSelectedSlot();
            if (!SwapLimit.select(axe)) return;                     // one swap per tick: try again next tick
            if (prevSlot < 0) prevSlot = cur;
        }
        if (!autoAttack.value) return;
        if (++wait <= delay.intValue()) return;
        wait = 0;
        AttackPackets.hit(target);                                  // syncs the selected slot to the server first
    }

    /** A shield only blocks hits that come from in front of its holder. */
    private boolean facingUs(Player p) {
        Vec3 to = mc.player.getEyePosition().subtract(p.getEyePosition()).normalize();
        return p.getViewVector(1.0f).dot(to) > 0.0;
    }

    private static boolean isAxe(ItemStack s) { return !s.isEmpty() && s.is(ItemTags.AXES); }

    private void restore() {
        if (prevSlot >= 0 && switchBack.value && mc.player != null && !SwapLimit.select(prevSlot)) return;   // retried next tick
        prevSlot = -1;
    }
}
