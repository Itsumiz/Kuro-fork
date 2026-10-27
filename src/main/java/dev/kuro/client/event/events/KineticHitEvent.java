package dev.kuro.client.event.events;

import net.minecraft.world.entity.Entity;

public class KineticHitEvent {
    private final Entity target;
    public KineticHitEvent(Entity target) { this.target = target; }
    public Entity getTarget() { return target; }
}