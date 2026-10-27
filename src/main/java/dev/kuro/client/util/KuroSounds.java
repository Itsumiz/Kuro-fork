package dev.kuro.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.HashMap;
import java.util.Map;

/** Plays Kuro's own UI sounds (assets/kuro/sounds/*.ogg, registered in sounds.json). Pass the file name without ".ogg". */
public final class KuroSounds {
    private static final Map<String, SoundEvent> CACHE = new HashMap<>();
    private KuroSounds() {}

    public static SimpleSoundInstance play(String name) { return play(name, 1.0f, 1.0f); }

    public static SimpleSoundInstance play(String name, float pitch, float volume) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSoundManager() == null) return null;   // too early (mod init) or shutting down
        SoundEvent ev = CACHE.computeIfAbsent(name, n -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("kuro", n)));
        SimpleSoundInstance si = SimpleSoundInstance.forUI(ev, pitch, volume);
        mc.getSoundManager().play(si);
        return si;
    }
}
