package dev.kuro.client.module.impl;

import dev.kuro.client.KuroClient;
import dev.kuro.client.module.Category;
import dev.kuro.client.module.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * Master switch. Bind a key to it (middle-click it in the menu, then press a key).
 * Pressing it turns every module that is on off and remembers which ones; pressing it again turns
 * exactly those back on. If nothing was remembered it turns everything on except the modules that
 * only make sense when used deliberately (Freecam, Flight, Ding Dong Builder).
 */
public class ToggleAll extends Module {
    private final List<Module> remembered = new ArrayList<>();

    public ToggleAll() { super("Toggle All", Category.PLAYER); }

    @Override
    public void toggle() {
        int before = 0, after = 0;
        for (Module m : KuroClient.modules.modules) if (m.isEnabled()) before++;
        Notifications.quiet(true);                      // one summary toast instead of one per module
        try { toggleAll(); } finally { Notifications.quiet(false); }
        for (Module m : KuroClient.modules.modules) if (m.isEnabled()) after++;
        if (after != before) Notifications.info("Toggle All", Math.abs(after - before) + " module" + (Math.abs(after - before) == 1 ? "" : "s") + (after > before ? " enabled" : " disabled"), after > before);
    }

    private void toggleAll() {
        List<Module> all = KuroClient.modules.modules;

        List<Module> active = new ArrayList<>();
        for (Module m : all) if (m != this && !(m instanceof SidebarUi) && !(m instanceof BlobUi) && !(m instanceof GlassUi) && m.isEnabled()) active.add(m);

        if (!active.isEmpty()) {                       // something is on -> switch everything off
            remembered.clear();
            remembered.addAll(active);
            for (Module m : active) m.setEnabled(false);
            return;
        }

        if (!remembered.isEmpty()) {                   // all off -> bring back what was on
            for (Module m : remembered) m.setEnabled(true);
            remembered.clear();
            return;
        }

        for (Module m : all) {                         // nothing remembered -> enable everything sensible
            if (m == this || m instanceof SidebarUi || m instanceof BlobUi || m instanceof GlassUi || m instanceof Freecam || m instanceof Flight || m instanceof DingDongBuilder
                    || m instanceof AutoWalk || m instanceof AutoTunnel || m instanceof AutoMiner
                    || m instanceof Freelook || m instanceof InvSorter || m instanceof WaypointAdd || m instanceof CoordShare
                    || m instanceof Macro || m instanceof MacroRecord || m instanceof BaritoneControl || m instanceof AutoFish
                    || m instanceof ElytraSwap) continue;
            m.setEnabled(true);
        }
    }
}
