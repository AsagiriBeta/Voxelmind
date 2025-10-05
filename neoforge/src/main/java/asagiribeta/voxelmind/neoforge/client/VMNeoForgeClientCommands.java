package asagiribeta.voxelmind.neoforge.client;

import asagiribeta.voxelmind.client.ClientInit;
import asagiribeta.voxelmind.client.agent.AIAgentController;
import asagiribeta.voxelmind.client.util.ScreenshotUtil;
import asagiribeta.voxelmind.config.Config;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class VMNeoForgeClientCommands {
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        var d = event.getDispatcher();
        d.register(Commands.literal("vm")
            .then(Commands.literal("say")
                .then(Commands.argument("text", StringArgumentType.greedyString())
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
            .then(Commands.literal("conv")
                .then(Commands.literal("show")
                    .then(Commands.argument("lines", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,200))
                        .executes(ctx -> {
                            int lines = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "lines");
                            var ctrl = ClientInit.getController();
                            String snap = ctrl.getConversationSnapshot(lines);
                            for (String line : snap.split("\n")) if (!line.isBlank()) Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[Conv] " + line));
                            return 1;
                        })
                    )
                    .executes(ctx -> {
                        var ctrl = ClientInit.getController();
                        String snap = ctrl.getConversationSnapshot(20);
                        for (String line : snap.split("\n")) if (!line.isBlank()) Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[Conv] " + line));
                        return 1;
                    })
                )
                .then(Commands.literal("clear").executes(ctx -> { ClientInit.getController().clearConversation(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Conversation cleared")); return 1; }))
                .then(Commands.literal("limit")
                    .then(Commands.literal("get").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_conversation_limit=" + Config.get().aiConversationLimit())); return 1; }))
                    .then(Commands.literal("set")
                        .then(Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4,200))
                            .executes(ctx -> { int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value"); Config.get().setAiConversationLimit(v); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_conversation_limit=" + v)); return 1; })
                        )
                    )
                )
            )
            .then(Commands.literal("status").executes(ctx -> {
                var ctrl = ClientInit.getController();
                var mode = ctrl.getMode();
                boolean dbg = Config.get().debug();
                boolean ansOnly = Config.get().observeAnswerOnly();
                boolean loose = Config.get().autoReplyLoose();
                Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Mode=" + mode + ", Debug=" + dbg + ", AnswerOnly=" + ansOnly + ", LooseReply=" + loose));
                return 1;
            }))
            .then(Commands.literal("debug")
                .then(Commands.literal("on").executes(ctx -> { Config.get().setDebug(true); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Debug ON")); return 1; }))
                .then(Commands.literal("off").executes(ctx -> { Config.get().setDebug(false); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Debug OFF")); return 1; }))
                .then(Commands.literal("status").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Debug=" + Config.get().debug())); return 1; }))
            )
            .then(Commands.literal("now").executes(ctx -> { ClientInit.getController().triggerDecisionNow(Minecraft.getInstance()); return 1; }))
            .then(Commands.literal("enable").executes(ctx -> { var c=ClientInit.getController(); c.setMode(AIAgentController.AgentMode.CONTROL); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] CONTROL mode")); return 1; }))
            .then(Commands.literal("observe").executes(ctx -> { var c=ClientInit.getController(); c.setMode(AIAgentController.AgentMode.OBSERVE); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] OBSERVE mode")); return 1; }))
            .then(Commands.literal("disable").executes(ctx -> { var c=ClientInit.getController(); c.setMode(AIAgentController.AgentMode.DISABLED); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Disabled")); return 1; }))
            .then(Commands.literal("lockradius")
                .then(Commands.literal("get").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Target lock radius=" + Config.get().targetLockRadius())); return 1; }))
                .then(Commands.literal("set")
                    .then(Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,128))
                        .executes(ctx -> { int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value"); Config.get().setTargetLockRadius(v); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Target lock radius=" + v)); return 1; })
                    )
                )
            )
            .then(Commands.literal("chat")
                .then(Commands.literal("dedup")
                    .then(Commands.literal("get").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_chat_dedup_ticks=" + Config.get().aiChatDedupTicks())); return 1; }))
                    .then(Commands.literal("set")
                        .then(Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(20,20000))
                            .executes(ctx -> { int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value"); Config.get().setAiChatDedupTicks(v); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_chat_dedup_ticks=" + v)); return 1; })
                        )
                    )
                )
                .then(Commands.literal("mininterval")
                    .then(Commands.literal("get").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_chat_min_interval_ticks=" + Config.get().aiChatMinIntervalTicks())); return 1; }))
                    .then(Commands.literal("set")
                        .then(Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0,2000))
                            .executes(ctx -> { int v = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value"); Config.get().setAiChatMinIntervalTicks(v); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_chat_min_interval_ticks=" + v)); return 1; })
                        )
                    )
                )
                .then(Commands.literal("autoreply")
                    .then(Commands.literal("on").executes(ctx -> { Config.get().setAiAutoReply(true); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] AI auto reply ON")); return 1; }))
                    .then(Commands.literal("off").executes(ctx -> { Config.get().setAiAutoReply(false); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] AI auto reply OFF")); return 1; }))
                    .then(Commands.literal("status").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] ai_auto_reply=" + Config.get().aiAutoReply())); return 1; }))
                )
                .then(Commands.literal("answeronly")
                    .then(Commands.literal("on").executes(ctx -> { Config.get().setObserveAnswerOnly(true); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] observe_answer_only=ON")); return 1; }))
                    .then(Commands.literal("off").executes(ctx -> { Config.get().setObserveAnswerOnly(false); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] observe_answer_only=OFF")); return 1; }))
                    .then(Commands.literal("status").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] observe_answer_only=" + Config.get().observeAnswerOnly())); return 1; }))
                )
                .then(Commands.literal("loosereply")
                    .then(Commands.literal("on").executes(ctx -> { Config.get().setAutoReplyLoose(true); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] auto_reply_loose=ON")); return 1; }))
                    .then(Commands.literal("off").executes(ctx -> { Config.get().setAutoReplyLoose(false); Config.save(); Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] auto_reply_loose=OFF")); return 1; }))
                    .then(Commands.literal("status").executes(ctx -> { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] auto_reply_loose=" + Config.get().autoReplyLoose())); return 1; }))
                )
            )
            .then(Commands.literal("sstest").executes(ctx -> {
                var mc = Minecraft.getInstance();
                ScreenshotUtil.captureAsync(mc, data -> mc.execute(() -> mc.gui.getChat().addMessage(Component.literal("[VoxelMind] sstest bytes=" + (data==null?"null":data.length)))));
                return 1;
            }))
            .then(Commands.literal("ssinfo").executes(ctx -> {
                Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] SS " + ScreenshotUtil.debugInfo()));
                return 1;
            }))
        );
    }
}
