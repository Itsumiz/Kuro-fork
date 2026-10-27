package dev.kuro.client.module;

public enum Category {
    RENDER("Render"),
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    PLAYER("Player"),
    BASE("Base Finding"),
    MISC("Misc");

    public final String label;
    Category(String label) { this.label = label; }
}
