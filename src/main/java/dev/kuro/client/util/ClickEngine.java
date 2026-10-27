package dev.kuro.client.util;

import java.util.Random;

public class ClickEngine {
    private final Random random = new Random();
    private long lastClickTime = 0;
    private final boolean perfectSwing;
    private final int minCps;
    private final int maxCps;

    public ClickEngine(boolean perfectSwing, int minCps, int maxCps) {
        this.perfectSwing = perfectSwing;
        this.minCps = minCps;
        this.maxCps = maxCps;
    }

    public boolean canClick(float attackStrength) {
        long now = System.currentTimeMillis();
        if (perfectSwing) {
            return attackStrength >= 1.0f;
        }
        
        int cps = minCps + random.nextInt(Math.max(1, maxCps - minCps));
        long delay = 1000 / Math.max(1, cps);
        
        if (now - lastClickTime >= delay) {
            lastClickTime = now;
            return true;
        }
        return false;
    }
}