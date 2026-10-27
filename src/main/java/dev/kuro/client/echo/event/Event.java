package dev.kuro.client.echo.event;

import dev.kuro.client.event.EventBus;

/** hack.echo.client.event.Event on top of Kuro's EventBus (listeners are exact-class @EventHandler methods). */
public abstract class Event {

    public final Stage stage;
    public boolean cancelled;

    protected Event(Stage stage) {
        this.stage = stage;
        cancelled = false;
    }

    protected Event() {
        this(Stage.NONE);
    }

    public boolean call() {
        EventBus.INSTANCE.post(this);
        return cancelled;
    }

    public final boolean isEarly() { return stage == Stage.EARLY; }
    public final boolean isPre() { return stage == Stage.PRE; }
    public final boolean isPost() { return stage == Stage.POST; }

    public final void cancel() { cancelled = true; }

    public enum Stage { NONE, EARLY, PRE, POST }
}
