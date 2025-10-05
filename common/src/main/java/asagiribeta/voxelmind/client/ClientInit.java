package asagiribeta.voxelmind.client;

import asagiribeta.voxelmind.Voxelmind;
import asagiribeta.voxelmind.client.agent.AIAgentController;
import asagiribeta.voxelmind.config.Config;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Common client bootstrap called by platform-specific entrypoints.
 */
public final class ClientInit {
    private static boolean initialized = false;

    // Toggle key for the AI agent
    public static final KeyMapping TOGGLE_AI_KEY = new KeyMapping(
            "key." + Voxelmind.MOD_ID + ".toggle_agent",
            GLFW.GLFW_KEY_P,
            "key.categories." + Voxelmind.MOD_ID
    );

    // Reload config key
    public static final KeyMapping RELOAD_CONFIG_KEY = new KeyMapping(
            "key." + Voxelmind.MOD_ID + ".reload_config",
            GLFW.GLFW_KEY_O,
            "key.categories." + Voxelmind.MOD_ID
    );

    private static final AIAgentController CONTROLLER = new AIAgentController();

    public static AIAgentController getController() { return CONTROLLER; }

    public static void init() {
        if (initialized) return;
        initialized = true;

        KeyMappingRegistry.register(TOGGLE_AI_KEY);
        KeyMappingRegistry.register(RELOAD_CONFIG_KEY);

        ClientLifecycleEvent.CLIENT_STARTED.register(mc -> {
            mc.execute(() -> mc.gui.getChat().addMessage(Component.literal("[VoxelMind] Client initialized")));
        });

        ClientTickEvent.CLIENT_POST.register(mc -> {
            // Handle toggle key
            while (TOGGLE_AI_KEY.consumeClick()) {
                var mode = CONTROLLER.getMode();
                AIAgentController.AgentMode next = switch (mode) {
                    case DISABLED -> AIAgentController.AgentMode.OBSERVE;
                    case OBSERVE -> AIAgentController.AgentMode.CONTROL;
                    case CONTROL -> AIAgentController.AgentMode.DISABLED;
                };
                CONTROLLER.setMode(next);
                mc.gui.getChat().addMessage(Component.literal("[VoxelMind] AI Mode: " + next));
            }
            // Handle reload config
            while (RELOAD_CONFIG_KEY.consumeClick()) {
                Config.load();
                mc.gui.getChat().addMessage(Component.literal("[VoxelMind] Config reloaded"));
            }

            CONTROLLER.onClientTick(mc);
        });
    }
}
