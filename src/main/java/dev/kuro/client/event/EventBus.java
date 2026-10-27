package dev.kuro.client.event;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class EventBus {
    public static final EventBus INSTANCE = new EventBus();
    
    private final Map<Class<?>, Map<Object, Method>> listeners = new HashMap<>();

    public void register(Object obj) {
        for (Method method : obj.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(EventHandler.class) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                listeners.computeIfAbsent(method.getParameterTypes()[0], k -> new HashMap<>()).put(obj, method);
            }
        }
    }

    public void unregister(Object obj) {
        for (Map<Object, Method> map : listeners.values()) {
            map.remove(obj);
        }
    }

    public void post(Object event) {
        Map<Object, Method> map = listeners.get(event.getClass());
        if (map == null) return;
        for (Map.Entry<Object, Method> entry : map.entrySet()) {
            try {
                entry.getValue().invoke(entry.getKey(), event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}