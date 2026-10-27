package dev.kuro.client.knife;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Colour palettes, picked from the held sword's material (netherite_sword -> NETHERITE, ...). */
final class KnifeMaterial {
    static final int BLADE = 0, BLADE_DARK = 1, EDGE = 2, HANDLE = 3, HANDLE_DARK = 4, METAL = 5, ACCENT = 6, WRAP = 7, GOLD = 8;
    /** Fixed "fade" colours used by the karambit (same for every sword material): FADE_0..FADE_(FADE_STEPS-1), then black scales. */
    static final int FADE_0 = 9, FADE_STEPS = 12, BLACK = FADE_0 + FADE_STEPS, BLACK_DARK = BLACK + 1, PURPLE = BLACK + 2, RED = BLACK + 3;
    private static final int[] FIXED = fixed();

    private static int[] fixed() {
        // purple -> magenta -> crimson -> orange -> gold, sampled from the reference screenshot
        int[][] stops = {{0x4A, 0x1A, 0x78}, {0x9A, 0x1C, 0x58}, {0xC8, 0x24, 0x3C}, {0xE0, 0x6A, 0x24}, {0xF2, 0xB4, 0x1C}};
        int[] out = new int[FADE_STEPS + 4];
        for (int i = 0; i < FADE_STEPS; i++) {
            float t = (float) i / (FADE_STEPS - 1) * (stops.length - 1);
            int a = Math.min(stops.length - 2, (int) t);
            float f = t - a;
            int r = Math.round(stops[a][0] + (stops[a + 1][0] - stops[a][0]) * f);
            int g = Math.round(stops[a][1] + (stops[a + 1][1] - stops[a][1]) * f);
            int b = Math.round(stops[a][2] + (stops[a + 1][2] - stops[a][2]) * f);
            out[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        out[FADE_STEPS] = 0xFF1C1C1E;       // black scales
        out[FADE_STEPS + 1] = 0xFF0B0B0D;   // darker rim / pins
        out[FADE_STEPS + 2] = 0xFF6A2CB0;   // purple inlay
        out[FADE_STEPS + 3] = 0xFFC8243C;   // red pin
        return out;
    }

    private static final KnifeMaterial DIAMOND = new KnifeMaterial(0x6FFFF0, 4187087, 1941139, 13828087, 1780531, 989213, 12109772, 4187087, 1391168, 15253850);
    private static final KnifeMaterial NETHERITE = new KnifeMaterial(16742973, 6445663, 3813942, 11773356, 1709592, 920333, 7234665, 13715758, 3806228, 13213770);
    private static final KnifeMaterial IRON = new KnifeMaterial(0xFFFFFF, 0xD9D9D9, 0x9B9B9B, 0xFFFFFF, 4862754, 2825490, 0x8E8E8E, 0xC8C8C8, 0x2B2B2B, 14267466);
    private static final KnifeMaterial GOLDEN = new KnifeMaterial(16769126, 16242762, 13015574, 16775096, 5905950, 3674128, 15188812, 16770688, 7216927, 15912795);
    private static final KnifeMaterial COPPER = new KnifeMaterial(0xFFAA77, 15043163, 11096108, 16763302, 3096378, 1844771, 6274975, 6274975, 2375738, 14263130);
    private static final KnifeMaterial STONE = new KnifeMaterial(0xDDDDDD, 0x9C9C9C, 0x666666, 0xC8C8C8, 4862754, 2825490, 0x7A7A7A, 0xA8A8A8, 3811868, 12558416);
    private static final KnifeMaterial WOOD = new KnifeMaterial(15782560, 12290902, 8279854, 14529402, 5914146, 3679763, 9071172, 13081442, 4861976, 13213770);

    final int[] palette;
    final int glow;

    private KnifeMaterial(int glow, int... palette) {
        this.glow = glow;
        this.palette = new int[palette.length + FIXED.length];
        for (int i = 0; i < palette.length; i++) this.palette[i] = 0xFF000000 | palette[i];
        System.arraycopy(FIXED, 0, this.palette, palette.length, FIXED.length);
    }

    static KnifeMaterial of(ItemStack stack) {
        return named(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
    }

    static KnifeMaterial named(String path) {
        if (path.startsWith("netherite")) return NETHERITE;
        if (path.startsWith("diamond")) return DIAMOND;
        if (path.startsWith("golden")) return GOLDEN;
        if (path.startsWith("copper")) return COPPER;
        if (path.startsWith("stone")) return STONE;
        if (path.startsWith("wooden")) return WOOD;
        return IRON;
    }

    static boolean fade(int m) { return m >= FADE_0 && m < FADE_0 + FADE_STEPS; }
    static boolean blade(int m) { return m == BLADE || m == BLADE_DARK || m == EDGE || fade(m); }
    static boolean metallic(int m) { return blade(m) || m == METAL || m == GOLD; }
}
