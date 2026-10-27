package dev.kuro.client.event.events;

import dev.kuro.client.module.Module;

public class SyntheticAttackEvent {
    private final Module source;
    private boolean cancelled = false;

    public SyntheticAttackEvent(Module source) {
        this.source = source;
    }

    public Module getSource() { return source; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}