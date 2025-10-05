package asagiribeta.voxelmind.fabric.client;

import asagiribeta.voxelmind.client.ClientInit;
import asagiribeta.voxelmind.client.agent.AIAgentController;
import asagiribeta.voxelmind.config.Config;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class VMFabricClientCommands {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("vm")
                    .then(ClientCommandManager.literal("say")
                            .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String msg = StringArgumentType.getString(ctx, "text");
                                        var ctrl = ClientInit.getController();
                                        ctrl.sayToAI(msg);
                                        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] You -> AI: " + msg));
                                        ctrl.triggerDecisionNow(Minecraft.getInstance());
                                        return 1;
                                    })
                            )
                    )
                    .then(ClientCommandManager.literal("conv")
                        .then(ClientCommandManager.literal("show")
                            .then(ClientCommandManager.argument("lines", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> {
                                    int lines = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "lines");
                                    var ctrl = ClientInit.getController();
                                    String snap = ctrl.getConversationSnapshot(lines);
                                    for (String line : snap.split("\n")) {
                                        if (!line.isBlank()) Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[Conv] " + line));
                                    }
                                    return 1;
                                })
                            )
                            .executes(ctx -> {
                                var ctrl = ClientInit.getController();
                                String snap = ctrl.getConversationSnapshot(20);
                                for (String line : snap.split("\n")) {
                                    if (!line.isBlank()) Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[Conv] " + line));
                                }
                                return 1;
                            })
                        )
                        .then(ClientCommandManager.literal("clear").executes(ctx -> { ClientInit.getController().clearConversation(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Conversation cleared")); return 1; }))
                        .then(ClientCommandManager.literal("limit")
                            .then(ClientCommandManager.literal("get").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_conversation_limit=" + Config.get().aiConversationLimit())); return 1; }))
                            .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 200))
                                    .executes(ctx -> { int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value"); Config.get().setAiConversationLimit(v); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_conversation_limit=" + v)); return 1; })
                                )
                            )
                        )
                    )
                    .then(ClientCommandManager.literal("status").executes(ctx -> {
                        var ctrl = ClientInit.getController();
                        var mode = ctrl.getMode();
                        boolean dbg = Config.get().debug();
                        boolean ansOnly = Config.get().observeAnswerOnly();
                        boolean loose = Config.get().autoReplyLoose();
                        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Mode=" + mode + ", Debug=" + dbg + ", AnswerOnly=" + ansOnly + ", LooseReply=" + loose));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("debug")
                        .then(ClientCommandManager.literal("on").executes(ctx -> { Config.get().setDebug(true); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Debug ON")); return 1; }))
                        .then(ClientCommandManager.literal("off").executes(ctx -> { Config.get().setDebug(false); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Debug OFF")); return 1; }))
                        .then(ClientCommandManager.literal("status").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Debug=" + Config.get().debug())); return 1; }))
                    )
                    .then(ClientCommandManager.literal("now").executes(ctx -> { ClientInit.getController().triggerDecisionNow(Minecraft.getInstance()); return 1; }))
                    .then(ClientCommandManager.literal("enable").executes(ctx -> { var c=ClientInit.getController(); c.setMode(AIAgentController.AgentMode.CONTROL); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] CONTROL mode")); return 1;}))
                    .then(ClientCommandManager.literal("observe").executes(ctx -> { var c=ClientInit.getController(); c.setMode(AIAgentController.AgentMode.OBSERVE); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] OBSERVE mode")); return 1;}))
                    .then(ClientCommandManager.literal("disable").executes(ctx -> { var c=ClientInit.getController(); c.setMode(AIAgentController.AgentMode.DISABLED); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Disabled")); return 1;}))
                    .then(ClientCommandManager.literal("lockradius")
                        .then(ClientCommandManager.literal("get").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Target lock radius=" + Config.get().targetLockRadius())); return 1; }))
                        .then(ClientCommandManager.literal("set")
                            .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 128))
                                .executes(ctx -> { int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value"); Config.get().setTargetLockRadius(v); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Target lock radius=" + v)); return 1; })
                            )
                        )
                    )
                    .then(ClientCommandManager.literal("chat")
                        .then(ClientCommandManager.literal("dedup")
                            .then(ClientCommandManager.literal("get").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_chat_dedup_ticks=" + Config.get().aiChatDedupTicks())); return 1; }))
                            .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(20, 20000))
                                    .executes(ctx -> { int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value"); Config.get().setAiChatDedupTicks(v); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_chat_dedup_ticks=" + v)); return 1; }))
                            )
                        )
                        .then(ClientCommandManager.literal("mininterval")
                            .then(ClientCommandManager.literal("get").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_chat_min_interval_ticks=" + Config.get().aiChatMinIntervalTicks())); return 1; }))
                            .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 2000))
                                    .executes(ctx -> { int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value"); Config.get().setAiChatMinIntervalTicks(v); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_chat_min_interval_ticks=" + v)); return 1; }))
                            )
                        )
                        .then(ClientCommandManager.literal("autoreply")
                            .then(ClientCommandManager.literal("on").executes(ctx -> { Config.get().setAiAutoReply(true); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] AI auto reply ON")); return 1; }))
                            .then(ClientCommandManager.literal("off").executes(ctx -> { Config.get().setAiAutoReply(false); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] AI auto reply OFF")); return 1; }))
                            .then(ClientCommandManager.literal("status").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_auto_reply=" + Config.get().aiAutoReply())); return 1; }))
                        )
                        .then(ClientCommandManager.literal("answeronly")
                            .then(ClientCommandManager.literal("on").executes(ctx -> { Config.get().setObserveAnswerOnly(true); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] observe_answer_only=ON")); return 1; }))
                            .then(ClientCommandManager.literal("off").executes(ctx -> { Config.get().setObserveAnswerOnly(false); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] observe_answer_only=OFF")); return 1; }))
                            .then(ClientCommandManager.literal("status").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] observe_answer_only=" + Config.get().observeAnswerOnly())); return 1; }))
                        )
                        .then(ClientCommandManager.literal("loosereply")
                            .then(ClientCommandManager.literal("on").executes(ctx -> { Config.get().setAutoReplyLoose(true); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] auto_reply_loose=ON")); return 1; }))
                            .then(ClientCommandManager.literal("off").executes(ctx -> { Config.get().setAutoReplyLoose(false); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] auto_reply_loose=OFF")); return 1; }))
                            .then(ClientCommandManager.literal("status").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] auto_reply_loose=" + Config.get().autoReplyLoose())); return 1; }))
                        )
                    )
            );
        });
    }
}
