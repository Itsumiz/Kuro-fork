package dev.kuro.client.util;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Custom menu fonts (assets/kuro/font/*.json -> TTF files, SIL Open Font License, see font/OFL.txt):
 * Instrument Sans for the UI text and Instrument Serif (italic for the accent word) for headings.
 * Every font JSON falls back to the vanilla font for glyphs the TTF does not have.
 */
public final class Fonts {
    private Fonts() {}

    private static Style style(String name) {
        return Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("kuro", name)));
    }

    public static final Style SANS = style("sans");
    public static final Style SANS_BOLD = style("sans_bold");
    public static final Style SERIF = style("serif");
    public static final Style SERIF_ITALIC = style("serif_italic");

    public static MutableComponent sans(String s) { return Component.literal(s).withStyle(SANS); }
    public static MutableComponent bold(String s) { return Component.literal(s).withStyle(SANS_BOLD); }
    public static MutableComponent serif(String s) { return Component.literal(s).withStyle(SERIF); }
    public static MutableComponent serifItalic(String s) { return Component.literal(s).withStyle(SERIF_ITALIC); }
}
