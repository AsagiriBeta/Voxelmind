package asagiribeta.voxelmind.fabric.client;

import asagiribeta.voxelmind.client.ClientInit;
import asagiribeta.voxelmind.client.agent.AIAgentController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class VoxelmindFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // This entrypoint is suitable for setting up client-specific logic, such as rendering.
        ClientInit.init();
        VMFabricClientCommands.register();
        // Listen for outgoing (local) chat messages (non-commands)
        ClientSendMessageEvents.CHAT.register(message -> {
            var ctrl = ClientInit.getController();
            var mc = Minecraft.getInstance();
            String name = mc.player != null ? mc.player.getGameProfile().getName() : "player";
            ctrl.onPlayerChat(name, message, true);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            var ctrl = ClientInit.getController();
            var mc = Minecraft.getInstance();
            String localName = mc.player != null ? mc.player.getGameProfile().getName() : "player";
            String senderName = sender != null ? sender.getName() : "?";
            boolean isLocal = senderName.equals(localName);
            ctrl.onPlayerChat(senderName, message.getString(), isLocal);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            var ctrl = ClientInit.getController();
            ctrl.onPlayerChat("server", message.getString(), false);
        });
    }
}
