package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import dev.kuro.client.setting.Setting;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Longer interaction range for your own player (applied in PlayerEntityMixin). Vanilla is 4.5 for blocks
 * and 3.0 for entities. Servers check reach themselves, so values well above vanilla usually get rejected
 * or flagged by anti-cheat. Hit chance (%) decides, once per tick, whether the longer entity reach is used at all:
 * 100 = always, 50 = about half of the ticks, 0 = never (vanilla reach).
 */
public class Reach extends Module {
    private static Reach instance;
    private final Setting.Num blockReach = add(new Setting.Num("Block reach", 6.0, 4.5, 8.0, 0.5));
    private final Setting.Num entityReach = add(new Setting.Num("Entity reach", 4.0, 3.0, 6.0, 0.5));

    private final Setting.Num chance = add(new Setting.Num("Hit chance (%)", 100, 0, 100, 5));
    private boolean extended = true;   // this tick's roll: true = the longer entity reach applies

    public Reach() { super("Reach", Category.COMBAT); instance = this; }

    @Override protected void onEnable() { roll(); }

    /** One roll per client tick, so a swing and the crosshair check in the same tick always agree. */
    @Override public void onPreTick() { roll(); }

    private void roll() { extended = chance.value >= 100 || ThreadLocalRandom.current().nextDouble() * 100.0 < chance.value; }

    /** @return the extended block range, or a negative number when the module is off. */
    public static double block() { return instance != null && instance.isEnabled() ? instance.blockReach.value : -1; }
    /** Entity reach is only extended on ticks that pass the Hit chance roll (otherwise vanilla reach applies). */
    public static double entity() { return instance != null && instance.isEnabled() && instance.extended ? instance.entityReach.value : -1; }
}
