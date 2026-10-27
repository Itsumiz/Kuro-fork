package dev.kuro.client;

import dev.kuro.client.command.CommandManager;
import dev.kuro.client.config.ConfigManager;
import dev.kuro.client.event.EventBus;
import dev.kuro.client.event.events.MotionEvent;
import dev.kuro.client.event.events.RenderEvent;
import dev.kuro.client.event.events.TickEvent;
import dev.kuro.client.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class KuroClient implements ClientModInitializer {
    public static ModuleManager modules;

    @Override
    public void onInitializeClient() {
        dev.kuro.client.util.ShaderUi.init();      // registers the UI shader pipelines before the first frame
        modules = new ModuleManager();
        ConfigManager.init();
        CommandManager.init();
        dev.kuro.client.util.WorldGfx.init();
        
        // Inject RenderEvent before the world renders
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(ctx -> {
            float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            EventBus.INSTANCE.post(new RenderEvent(partialTick));
            modules.onWorld(ctx);
        });
        
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            dev.kuro.client.util.SwapLimit.nextTick();
            dev.kuro.client.util.ServerSync.nextTick();
            dev.kuro.client.util.TickPhase.startTick();
            dev.kuro.client.module.impl.AttackPackets.flush();     // hits held back from the end of last tick go out first, before any movement packet
            
            // --- EVENT BUS INTEGRATION ---
            EventBus.INSTANCE.post(new TickEvent.Pre());
            EventBus.INSTANCE.post(new MotionEvent(MotionEvent.State.PRE));
            
            modules.onPreTick();
        });
        
        ClientTickEvents.END_CLIENT_TICK.register(client -> { 
            modules.onTick(); 
            dev.kuro.client.util.MenuKey.tick(client);
            dev.kuro.client.module.impl.FakeLoading.tick(client);   // first start: asks which key opens the menu
            ConfigManager.tick(); 
            
            // --- EVENT BUS INTEGRATION ---
            EventBus.INSTANCE.post(new TickEvent.Post());
            EventBus.INSTANCE.post(new MotionEvent(MotionEvent.State.POST));
        });
        
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> dev.kuro.client.util.ViewLock.release(client));
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("kuro", "modules"),
                (context, tickCounter) -> modules.onHud(context));
    }
}