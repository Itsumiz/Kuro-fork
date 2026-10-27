package dev.kuro.client.gui;

import dev.kuro.client.util.KuroSounds;
import dev.kuro.client.util.Look;
import dev.kuro.client.util.SdfText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A made-up loading screen: a progress bar that fills over the set time, with a sound. Esc skips it. */
public class FakeLoadingScreen extends Screen {
    public static final String SOUND = "fake_loading";

    private final Screen parent;
    private final long durationMs;
    private final boolean withSound;
    private final long start = System.currentTimeMillis();
    private boolean done;
    private net.minecraft.client.resources.sounds.SimpleSoundInstance playing;

    public FakeLoadingScreen(Screen parent, double seconds, boolean withSound) {
        super(Component.literal("Loading"));
        this.parent = parent;
        this.durationMs = Math.max(500L, (long) (seconds * 1000));
        this.withSound = withSound;
        if (withSound) playing = KuroSounds.play(SOUND);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xFF000000);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        long age = System.currentTimeMillis() - start;
        float t = Math.min(1f, age / (float) durationMs);
        float eased = t * t * (3f - 2f * t);                      // starts and ends slowly, like a real load

        String title = "Loading";
        SdfText.ui(g, title, (width - SdfText.w(title)) / 2f, height / 2 - 20, Look.TEXT, true);
        int bw = Math.min(220, width - 40), bh = 4;
        int bx = (width - bw) / 2, by = height / 2;
        g.fill(bx, by, bx + bw, by + bh, 0xFF22252D);
        g.fill(bx, by, bx + Math.round(bw * eased), by + bh, Look.ACCENT);
        String pct = Math.round(eased * 100) + "%";
        SdfText.ui(g, pct, (width - SdfText.w(pct)) / 2f, by + 12, Look.MUTED, false);
        String hint = "Esc to skip";
        SdfText.ui(g, hint, (width - SdfText.w(hint)) / 2f, height - 20, Look.MUTED, false);

        if (t >= 1f && age > durationMs + 250) finish();
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.input() == GLFW.GLFW_KEY_ESCAPE) { finish(); return true; }
        return true;                                            // swallow everything else while it plays
    }

    private void finish() {
        if (done) return;
        done = true;
        if (playing != null && minecraft != null) minecraft.getSoundManager().stop(playing);   // Esc cuts the sound too
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override public void onClose() { finish(); }
}
