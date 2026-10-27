package dev.kuro.client.setting;

import net.minecraft.util.Mth;

public abstract class Setting {
    public final String name;

    protected Setting(String name) { this.name = name; }

    public static class Bool extends Setting {
        public boolean value;
        public Bool(String name, boolean value) { super(name); this.value = value; }
    }

    public static class Num extends Setting {
        public double value;
        public final double min, max, step;

        public Num(String name, double value, double min, double max, double step) {
            super(name);
            this.value = value; this.min = min; this.max = max; this.step = step;
        }

        public void setFraction(double f) {
            double v = min + Mth.clamp(f, 0.0, 1.0) * (max - min);
            value = Mth.clamp(Math.round(v / step) * step, min, max);
        }

        /** Optional names for a whole-number selector: the minimum value shows labels[0], the next labels[1], ... */
        public String[] labels;

        public Num labelled(String... names) { this.labels = names; return this; }

        /** What the GUI shows next to the slider: the label for selectors, otherwise the number. */
        public String display() {
            if (labels != null) {
                int i = (int) Math.round(value - min);
                if (i >= 0 && i < labels.length) return labels[i];
            }
            return step >= 1 ? String.valueOf(intValue()) : String.format(step < 0.1 ? "%.2f" : "%.1f", value);
        }

        public double fraction() { return (value - min) / (max - min); }
        public int intValue() { return (int) Math.round(value); }
    }

    /**
     * Colour setting. Plain = a rainbow strip (hue only). Call wheel(...) to make it a full colour wheel
     * (hue + saturation on the wheel, brightness on a bar underneath) in the click GUI.
     */
    public static class Hue extends Setting {
        public float hue;
        public float sat = 1f, val = 1f;
        public boolean wheel;
        public Hue(String name, float hue) { super(name); this.hue = hue; }

        /** Turns this into a colour wheel setting with the given default saturation / brightness (0..1). */
        public Hue wheel(float sat, float val) { this.wheel = true; this.sat = sat; this.val = val; return this; }
        public Hue wheel() { return wheel(0.78f, 1f); }

        /** 0xRRGGBB. Wheel settings use hue/sat/brightness; strip settings keep the old softened look. */
        public int rgb() {
            return Mth.hsvToRgb(hue, wheel ? sat : 0.78f, wheel ? val : 1f) & 0xFFFFFF;
        }
        public int argb(int alpha) {
            int c = wheel ? Mth.hsvToRgb(hue, sat, val) : Mth.hsvToRgb(hue, 1f, 1f);
            return (alpha << 24) | (c & 0xFFFFFF);
        }
    }

    public static class Button extends Setting {
        public final Runnable action;
        public Button(String name, Runnable action) { super(name); this.action = action; }
    }

    /** A text box: click it, type, press Enter to hand the text to onSubmit (the box then clears). */
    public static class Text extends Setting {
        public String value = "";
        public int maxLen = 32;   // raise for long values (tokens, URLs)
        public final java.util.function.Consumer<String> onSubmit;
        public Text(String name, java.util.function.Consumer<String> onSubmit) { super(name); this.onSubmit = onSubmit; }
        public void submit() {
            String v = value.trim();
            value = "";
            if (!v.isEmpty()) onSubmit.accept(v);
        }
    }
}
