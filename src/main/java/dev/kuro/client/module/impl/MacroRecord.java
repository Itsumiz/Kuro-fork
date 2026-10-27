package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.Mth;

/**
 * Bind a key to this: press it to start recording your key presses, mouse buttons and camera turns, press it
 * again to stop. The recording replaces the previous macro and is played back by the "Macro" module.
 */
public class MacroRecord extends Module {
    private static final int MAX_TICKS = 20 * 60 * 5;       // 5 minutes
    private float lastYaw, lastPitch;

    public MacroRecord() { super("Macro Record", Category.PLAYER); }

    @Override
    protected void onEnable() {
        if (mc.player == null) { setEnabled(false); return; }
        Macro m = Macro.get();
        if (m != null && m.isEnabled()) { Macro.say("Stop the macro first."); setEnabled(false); return; }
        Macro.frames.clear();
        Macro.recording = true;
        lastYaw = mc.player.getYRot();
        lastPitch = mc.player.getXRot();
        Macro.say("Recording... press the key again to stop.");
    }

    @Override
    protected void onDisable() {
        if (!Macro.recording) return;
        Macro.recording = false;
        var f = Macro.frames;
        while (!f.isEmpty() && f.get(f.size() - 1).mask() == 0
                && Math.abs(f.get(f.size() - 1).dyaw()) < 0.01f && Math.abs(f.get(f.size() - 1).dpitch()) < 0.01f)
            f.remove(f.size() - 1);                         // trim the idle ticks around pressing the stop key
        Macro.save();
        Macro.say("Saved " + f.size() + " ticks (" + String.format("%.1f", f.size() / 20.0) + "s).");
    }

    @Override
    public void onPreTick() {
        if (mc.player == null || mc.level == null) { setEnabled(false); return; }
        if (Macro.frames.size() >= MAX_TICKS) { Macro.say("Reached the 5 minute limit."); setEnabled(false); return; }

        int mask = 0;
        float dyaw = 0, dpitch = 0;
        if (mc.screen == null) {
            KeyMapping[] keys = Macro.keys();
            for (int i = 0; i < keys.length; i++) if (keys[i].isDown()) mask |= 1 << i;
            dyaw = Mth.wrapDegrees(mc.player.getYRot() - lastYaw);
            dpitch = mc.player.getXRot() - lastPitch;
        }
        lastYaw = mc.player.getYRot();
        lastPitch = mc.player.getXRot();
        Macro.frames.add(new Macro.Frame(mask, dyaw, dpitch));
    }
}
