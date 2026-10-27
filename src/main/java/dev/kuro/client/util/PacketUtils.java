package dev.kuro.client.util;

import dev.kuro.client.util.BlockPlacer;

public class PacketUtils {
    public static int getSelectedSlot() {
        return BlockPlacer.selected();
    }

    public static void setSelectedSlot(int slot) {
        BlockPlacer.selectForce(slot);
    }
}