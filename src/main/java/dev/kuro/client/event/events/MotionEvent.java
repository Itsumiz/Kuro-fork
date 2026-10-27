package dev.kuro.client.event.events;

public class MotionEvent {
    public enum State { PRE, POST }
    private final State state;
    public MotionEvent(State state) { this.state = state; }
    public State getState() { return state; }
}