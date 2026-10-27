package dev.kuro.client.module.impl;

import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;

/** Bind a key to this: each press saves a waypoint where you stand. It never stays "on". */
public class WaypointAdd extends Module {
    public WaypointAdd() { super("Add Waypoint", Category.RENDER); }

    @Override public void toggle() { Waypoints.get().addHere(); }
    @Override public void setEnabled(boolean value) { if (value) Waypoints.get().addHere(); }
}
