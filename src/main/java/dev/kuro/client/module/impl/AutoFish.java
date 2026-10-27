package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto Fish (Player): hold a fishing rod and switch it on. It casts, waits for the bite, reels in after a short human-ish
 * delay and casts again. A bite is noticed from the bobber's splash sound (with the bobber's sudden dip as a backup).
 * If nothing bites for the timeout it reels in and casts again, so a stuck bobber doesn't stall it. It only works while
 * a rod is in your main hand or offhand and no menu is open.
 */
public class AutoFish extends Module {
    private static AutoFish instance;
    private static final int GRACE = 40;               // ticks after casting during which splashes (landing) are ignored

    private final Setting.Bool autoCast = add(new Setting.Bool("Auto cast", true));
    private final Setting.Num reelDelay = add(new Setting.Num("Reel delay (ticks)", 6, 1, 20, 1));
    private final Setting.Num castDelay = add(new Setting.Num("Cast delay (ticks)", 20, 5, 60, 1));
    private final Setting.Num timeout = add(new Setting.Num("Recast timeout (s)", 45, 10, 120, 5));

    private enum Stage { WAIT, REEL, RECAST }
    private Stage stage = Stage.WAIT;
    private int timer, sinceCast;
    private boolean bite;

    public AutoFish() { super("Auto Fish", Category.PLAYER); instance = this; }

    @Override protected void onEnable() { reset(); }
    @Override protected void onDisable() { reset(); }

    private void reset() { stage = Stage.WAIT; timer = 0; sinceCast = 0; bite = false; }

    /** Called from the sound packet hook: the bobber's splash = a fish is on the line. */
    public static void onSound(String path, double x, double y, double z) {
        AutoFish a = instance;
        if (a == null || !a.isEnabled() || mc.player == null) return;
        if (!path.equals("entity.fishing_bobber.splash")) return;
        FishingHook hook = mc.player.fishing;
        if (hook == null) return;
        double dx = hook.getX() - x, dy = hook.getY() - y, dz = hook.getZ() - z;
        if (dx * dx + dy * dy + dz * dz < 4.0) a.bite = true;
    }

    private static int jitter() { return ThreadLocalRandom.current().nextInt(0, 3); }

    private void use(Player p, InteractionHand hand) {
        mc.gameMode.useItem(p, hand);
        p.swing(hand);
    }

    @Override
    public void onTick() {
        Player p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null || mc.screen != null || p.isSpectator()) return;
        InteractionHand hand = p.getMainHandItem().is(Items.FISHING_ROD) ? InteractionHand.MAIN_HAND
                : p.getOffhandItem().is(Items.FISHING_ROD) ? InteractionHand.OFF_HAND : null;
        if (hand == null) { reset(); return; }
        FishingHook hook = p.fishing;

        switch (stage) {
            case WAIT -> {
                if (hook == null) {
                    sinceCast = 0;
                    bite = false;
                    if (autoCast.value && --timer <= 0) { use(p, hand); timer = castDelay.intValue(); }
                    return;
                }
                sinceCast++;
                if (sinceCast > timeout.intValue() * 20) {          // stuck: reel in and start over
                    use(p, hand);
                    stage = Stage.RECAST;
                    timer = castDelay.intValue() + jitter();
                    return;
                }
                boolean dip = hook.isInWater() && hook.getDeltaMovement().y < -0.15;
                if (sinceCast > GRACE && (bite || dip)) {
                    bite = false;
                    stage = Stage.REEL;
                    timer = reelDelay.intValue() + jitter();
                }
            }
            case REEL -> {
                if (--timer > 0) return;
                if (hook != null) use(p, hand);
                stage = Stage.RECAST;
                timer = castDelay.intValue() + jitter();
            }
            case RECAST -> {
                if (--timer > 0) return;
                stage = Stage.WAIT;
                timer = 0;
            }
        }
    }
}
