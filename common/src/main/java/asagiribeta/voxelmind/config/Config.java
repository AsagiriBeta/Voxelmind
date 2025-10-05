package asagiribeta.voxelmind.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Config INSTANCE;

    @SerializedName("agent_url") private String agentUrl = "";
    @SerializedName("api_key") private String apiKey = "";
    @SerializedName("model") private String model = "";
    @SerializedName("decision_interval_ticks") private int decisionIntervalTicks = 5;
    @SerializedName("debug") private boolean debug = false;
    @SerializedName("allow_public_chat") private boolean allowPublicChat = false;
    @SerializedName("show_ai_prefix") private boolean showAIPrefix = true;

    // Radius (in blocks) used for auto target locking searches.
    @SerializedName("target_lock_radius")
    private int targetLockRadius = 8;

    // Primary action gating while aiming at a valid target/log within reach.
    @SerializedName("assist_only_primary_when_aiming")
    private boolean assistOnlyPrimaryWhenAiming = true;
    @SerializedName("assist_primary_reach_distance")
    private float assistPrimaryReachDistance = 4.5f;

    // New: AI chat dedup window and minimum interval to avoid spam / repetition.
    // If the same exact message appears again within aiChatDedupTicks it will be suppressed.
    @SerializedName("ai_chat_dedup_ticks") private int aiChatDedupTicks = 600; // 600 ticks ~30s default
    // Minimum ticks between ANY two AI chat messages (even if different) to throttle verbosity.
    @SerializedName("ai_chat_min_interval_ticks") private int aiChatMinIntervalTicks = 60; // 3s default
    // Maximum number of recent AI messages we keep for local heuristic (small memory footprint)
    @SerializedName("ai_chat_recent_limit") private int aiChatRecentLimit = 8;
    // New: auto reply to explicit user queries via command (future: natural chat detection)
    @SerializedName("ai_auto_reply") private boolean aiAutoReply = true;
    @SerializedName("ai_conversation_limit") private int aiConversationLimit = 30;

    // New config options
    @SerializedName("observe_answer_only") private boolean observeAnswerOnly = true; // when OBSERVE mode: only chat
    @SerializedName("auto_reply_loose") private boolean autoReplyLoose = false; // loose auto reply trigger (any message)
    @SerializedName("ai_local_echo_window_ticks") private int aiLocalEchoWindowTicks = 10; // ticks window to ignore own echoed messages
    @SerializedName("ai_no_repeat_consecutive") private boolean aiNoRepeatConsecutive = true; // suppress identical consecutive AI chat

    public static Config get() {
        if (INSTANCE == null) { synchronized (Config.class) { if (INSTANCE == null) load(); } }
        return INSTANCE;
    }

    public static void load() {
        Path path = getConfigPath();
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Config def = new Config();
                try (Writer w = Files.newBufferedWriter(path)) { GSON.toJson(def, w); }
                INSTANCE = def;
            } else {
                try (Reader r = Files.newBufferedReader(path)) {
                    INSTANCE = Objects.requireNonNullElse(GSON.fromJson(r, Config.class), new Config());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            INSTANCE = new Config();
        }
    }

    public static void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) { GSON.toJson(get(), w); }
        } catch (IOException e) { e.printStackTrace(); }
    }

    public static Path getConfigPath() { return Platform.getConfigFolder().resolve("voxelmind.json"); }

    // Getters
    public String agentUrl() { return agentUrl == null ? "" : agentUrl; }
    public String apiKey() { return apiKey == null ? "" : apiKey; }
    public String model() { return model == null ? "" : model; }
    public int decisionIntervalTicks() { return Math.max(1, decisionIntervalTicks); }
    public boolean debug() { return debug; }
    public boolean allowPublicChat() { return allowPublicChat; }
    public boolean showAIPrefix() { return showAIPrefix; }
    public int targetLockRadius() { return Math.max(1, targetLockRadius); }
    public boolean assistOnlyPrimaryWhenAiming() { return assistOnlyPrimaryWhenAiming; }
    public float assistPrimaryReachDistance() { return Math.max(1.0f, assistPrimaryReachDistance); }
    public int aiChatDedupTicks() { return Math.max(1, aiChatDedupTicks); }
    public int aiChatMinIntervalTicks() { return Math.max(0, aiChatMinIntervalTicks); }
    public int aiChatRecentLimit() { return Math.max(1, aiChatRecentLimit); }
    public boolean aiAutoReply() { return aiAutoReply; }
    public int aiConversationLimit() { return Math.max(4, aiConversationLimit); }
    public boolean observeAnswerOnly() { return observeAnswerOnly; }
    public boolean autoReplyLoose() { return autoReplyLoose; }
    public int aiLocalEchoWindowTicks() { return Math.max(1, aiLocalEchoWindowTicks); }
    public boolean aiNoRepeatConsecutive() { return aiNoRepeatConsecutive; }

    // Setters
    public void setAgentUrl(String v) { this.agentUrl = v == null ? "" : v; }
    public void setApiKey(String v) { this.apiKey = v == null ? "" : v; }
    public void setModel(String v) { this.model = v == null ? "" : v; }
    public void setDecisionIntervalTicks(int v) { this.decisionIntervalTicks = Math.max(1, v); }
    public void setDebug(boolean v) { this.debug = v; }
    public void setAllowPublicChat(boolean v) { this.allowPublicChat = v; }
    public void setShowAIPrefix(boolean v) { this.showAIPrefix = v; }
    public void setTargetLockRadius(int v) { this.targetLockRadius = Math.max(1, v); }
    public void setAssistOnlyPrimaryWhenAiming(boolean v) { this.assistOnlyPrimaryWhenAiming = v; }
    public void setAssistPrimaryReachDistance(float v) { this.assistPrimaryReachDistance = v; }
    public void setAiChatDedupTicks(int v) { this.aiChatDedupTicks = Math.max(1, v); }
    public void setAiChatMinIntervalTicks(int v) { this.aiChatMinIntervalTicks = Math.max(0, v); }
    public void setAiChatRecentLimit(int v) { this.aiChatRecentLimit = Math.max(1, v); }
    public void setAiAutoReply(boolean v) { this.aiAutoReply = v; }
    public void setAiConversationLimit(int v) { this.aiConversationLimit = Math.max(4, v); }
    public void setObserveAnswerOnly(boolean v) { this.observeAnswerOnly = v; }
    public void setAutoReplyLoose(boolean v) { this.autoReplyLoose = v; }
    public void setAiLocalEchoWindowTicks(int v) { this.aiLocalEchoWindowTicks = Math.max(1, v); }
    public void setAiNoRepeatConsecutive(boolean v) { this.aiNoRepeatConsecutive = v; }
}
