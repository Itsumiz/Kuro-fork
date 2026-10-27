package dev.kuro.client.echo;

import net.minecraft.client.Minecraft;

/** Same role as hack.echo.client.utils.Imports: gives the ported classes a shared {@code mc}. */
public interface Imports {
    Minecraft mc = Minecraft.getInstance();
}
