package dev.kuro.client.util;

import dev.kuro.client.module.impl.BaritoneControl;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin reflection layer over the Baritone API, so Kuro compiles and runs with or without Baritone installed.
 * Same approach as BaritoneControl.sendPlainChat: look the classes up by name at runtime, never import them.
 *
 * Settings changed through {@link #set} are remembered, and {@link #restoreAll} puts every one of them back
 * exactly as it was, so switching a module off leaves your own Baritone setup untouched.
 */
public final class BaritoneBridge {
    private static final String API = "baritone.api.BaritoneAPI";
    private static final String PROVIDER = "baritone.api.IBaritoneProvider";
    private static final String BARITONE = "baritone.api.IBaritone";
    private static final String BUILDER = "baritone.api.process.IBuilderProcess";
    private static final String GOAL_PROCESS = "baritone.api.process.ICustomGoalProcess";
    private static final String PATHING = "baritone.api.behavior.IPathingBehavior";
    private static final String GOAL = "baritone.api.pathing.goals.Goal";
    private static final String GET_TO_BLOCK = "baritone.api.pathing.goals.GoalGetToBlock";

    /** setting name -> value it had before we touched it */
    private static final Map<String, Object> SAVED = new HashMap<>();

    private static volatile String lastError;

    private BaritoneBridge() {}

    private static void note(Throwable t) {
        Throwable c = t;
        while ((c instanceof java.lang.reflect.InvocationTargetException || c instanceof ExceptionInInitializerError) && c.getCause() != null) c = c.getCause();
        lastError = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }

    /** Why the last reflection call failed (for chat), or null. */
    public static String lastError() { return lastError; }

    /** True when Baritone's API can be driven by reflection (settings, builder state). */
    public static boolean apiWorks() {
        lastError = null;
        try {
            Object b = builder();
            Class.forName(BUILDER).getMethod("isActive").invoke(b);
            Object settings = Class.forName(API).getMethod("getSettings").invoke(null);
            Object setting = settings.getClass().getField("allowSprint").get(settings);
            setting.getClass().getField("value").get(setting);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e);
            return false;
        }
    }

    public static boolean installed() { return BaritoneControl.installed(); }

    // ------------------------------------------------------------------ reflection plumbing

    private static Object primary() throws ReflectiveOperationException {
        Object provider = Class.forName(API).getMethod("getProvider").invoke(null);
        return Class.forName(PROVIDER).getMethod("getPrimaryBaritone").invoke(provider);
    }

    private static Object invoke0(Object target, String iface, String method) throws ReflectiveOperationException {
        java.lang.reflect.Method m = Class.forName(iface).getMethod(method);
        try { m.setAccessible(true); } catch (RuntimeException ignored) {}
        return m.invoke(target);
    }

    private static Object builder() throws ReflectiveOperationException {
        return invoke0(primary(), BARITONE, "getBuilderProcess");
    }

    // ------------------------------------------------------------------ settings

    /**
     * Sets a Baritone setting by its field name (e.g. "smoothLook"). The old value is remembered the first time.
     * Returns false when this Baritone build has no such setting or the value does not fit its type.
     */
    public static boolean set(String name, Object value) {
        try {
            Object settings = Class.forName(API).getMethod("getSettings").invoke(null);
            Object setting = settings.getClass().getField(name).get(settings);
            Field valueField = setting.getClass().getField("value");
            try { valueField.setAccessible(true); } catch (RuntimeException ignored) {}
            Object old = valueField.get(setting);
            Object coerced = coerce(old, value);
            if (coerced == null) return false;
            SAVED.putIfAbsent(name, old);
            valueField.set(setting, coerced);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e);
            return false;
        }
    }

    /**
     * Adds an entry to a list-type Baritone setting (e.g. buildIgnoreProperties) without losing what is already in it.
     * The original list is remembered and put back by {@link #restoreAll}. Returns false if this Baritone has no such setting.
     */
    public static boolean addToList(String name, String entry) {
        try {
            Object settings = Class.forName(API).getMethod("getSettings").invoke(null);
            Object setting = settings.getClass().getField(name).get(settings);
            Field valueField = setting.getClass().getField("value");
            try { valueField.setAccessible(true); } catch (RuntimeException ignored) {}
            Object old = valueField.get(setting);
            if (!(old instanceof java.util.List<?> list)) return false;
            if (list.contains(entry)) return true;
            java.util.List<Object> copy = new java.util.ArrayList<>(list);
            copy.add(entry);
            SAVED.putIfAbsent(name, old);
            valueField.set(setting, copy);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e);
            return false;
        }
    }

    /**
     * Makes a list-type Baritone setting (e.g. buildIgnoreProperties) equal to "the list it had before we touched it" plus the
     * given entries. Calling it again with other entries replaces the earlier ones; an empty list puts the original back.
     * {@link #restoreAll} still restores the original on switch-off.
     */
    public static boolean setListEntries(String name, java.util.List<String> entries) {
        try {
            Object settings = Class.forName(API).getMethod("getSettings").invoke(null);
            Object setting = settings.getClass().getField(name).get(settings);
            Field valueField = setting.getClass().getField("value");
            try { valueField.setAccessible(true); } catch (RuntimeException ignored) {}
            Object current = valueField.get(setting);
            if (!(current instanceof java.util.List<?>)) return false;
            Object base = SAVED.computeIfAbsent(name, k -> current);
            if (!(base instanceof java.util.List<?> baseList)) return false;
            java.util.List<Object> copy = new java.util.ArrayList<>(baseList);
            for (String e : entries) if (!copy.contains(e)) copy.add(e);
            valueField.set(setting, copy);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e);
            return false;
        }
    }

    private static Object coerce(Object old, Object value) {
        if (old == null || value == null) return null;
        if (old instanceof Boolean && value instanceof Boolean) return value;
        if (value instanceof Number n) {
            if (old instanceof Integer) return n.intValue();
            if (old instanceof Double) return n.doubleValue();
            if (old instanceof Float) return n.floatValue();
            if (old instanceof Long) return n.longValue();
        }
        return old.getClass() == value.getClass() ? value : null;
    }

    /** Puts every setting changed through {@link #set} back to the value it had. */
    public static void restoreAll() {
        if (SAVED.isEmpty()) return;
        try {
            Object settings = Class.forName(API).getMethod("getSettings").invoke(null);
            for (Map.Entry<String, Object> e : SAVED.entrySet()) {
                try {
                    Object setting = settings.getClass().getField(e.getKey()).get(settings);
                    setting.getClass().getField("value").set(setting, e.getValue());
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        } finally {
            SAVED.clear();
        }
    }

    // ------------------------------------------------------------------ builder state

    /** A schematic is loaded in the builder (it is building, or paused). False once it is finished or cancelled. */
    public static boolean builderActive() {
        try { return Boolean.TRUE.equals(invoke0(builder(), BUILDER, "isActive")); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e); return false; }
    }

    /** The builder is paused: either we paused it, or Baritone did because it ran out of materials. */
    public static boolean builderPaused() {
        try { return Boolean.TRUE.equals(invoke0(builder(), BUILDER, "isPaused")); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e); return false; }
    }

    public static boolean pauseBuilder() {
        try { invoke0(builder(), BUILDER, "pause"); return true; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e); return false; }
    }

    public static boolean resumeBuilder() {
        try { invoke0(builder(), BUILDER, "resume"); return true; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e); return false; }
    }

    // ------------------------------------------------------------------ walking

    /** Starts walking next to (or onto) a block, e.g. a chest. The builder stays loaded: it only yields to this goal. */
    public static boolean goToBlock(BlockPos pos) {
        try {
            Object goal = Class.forName(GET_TO_BLOCK).getConstructor(BlockPos.class).newInstance(pos);
            Object process = invoke0(primary(), BARITONE, "getCustomGoalProcess");
            Class.forName(GOAL_PROCESS).getMethod("setGoalAndPath", Class.forName(GOAL)).invoke(process, goal);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e);
            return false;
        }
    }

    /** True while the walk started by {@link #goToBlock} is still going. */
    public static boolean goalActive() {
        try {
            Object process = invoke0(primary(), BARITONE, "getCustomGoalProcess");
            return Boolean.TRUE.equals(invoke0(process, GOAL_PROCESS, "isActive"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e);
            return false;
        }
    }

    /**
     * The next few feet positions of the path Baritone is walking right now (empty when it has none or this Baritone
     * build names the classes differently). Used to spot a closed door ahead before the player walks into it.
     */
    public static java.util.List<BlockPos> upcomingPath(int n) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        try {
            Object pathing = invoke0(primary(), BARITONE, "getPathingBehavior");
            Object exec = invoke0(pathing, PATHING, "getCurrent");
            if (exec == null) return out;
            String ex = "baritone.api.pathing.path.IPathExecutor";
            Object posObj = invoke0(exec, ex, "getPosition");
            int from = posObj instanceof Integer i ? Math.max(0, i) : 0;
            Object path = invoke0(exec, ex, "getPath");
            if (path == null) return out;
            Object all = invoke0(path, "baritone.api.pathing.calc.IPath", "positions");
            if (!(all instanceof java.util.List<?> list)) return out;
            for (int i = from; i < list.size() && out.size() < n; i++) {
                if (list.get(i) instanceof BlockPos b) out.add(b.immutable());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // optional helper: the caller falls back to "I am stuck next to a door"
        }
        return out;
    }

    /** Stops all Baritone work (path, goal and the loaded schematic). */
    public static boolean cancelEverything() {
        try {
            Object pathing = invoke0(primary(), BARITONE, "getPathingBehavior");
            invoke0(pathing, PATHING, "cancelEverything");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            note(e);
            return false;
        }
    }
}
