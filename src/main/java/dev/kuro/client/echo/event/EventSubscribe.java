package dev.kuro.client.echo.event;

/**
 * Only the Priority enum of hack.echo.client.event.EventSubscribe is needed here (rotation control priority).
 * Listeners themselves use Kuro's @EventHandler.
 */
public final class EventSubscribe {
    private EventSubscribe() {}

    public enum Priority {
        HIGHEST,
        HIGH,
        NORMAL,
        LOW,
        LOWEST
    }
}
