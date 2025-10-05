package asagiribeta.voxelmind.client.agent;

import asagiribeta.voxelmind.client.input.InputApplier;
import asagiribeta.voxelmind.client.navigation.PathNavigator;
import asagiribeta.voxelmind.client.util.CrosshairUtil;
import asagiribeta.voxelmind.client.util.ScreenshotUtil;
import asagiribeta.voxelmind.client.util.TargetingUtil;
import asagiribeta.voxelmind.client.util.ChatSanitizer;
import asagiribeta.voxelmind.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AIAgentController {
    private static final Logger LOGGER = LogManager.getLogger("VoxelMind-AI");
    private AgentClient agent;
    private final InputApplier input = new InputApplier();
    // Replace boolean enabled with tri-state mode
    public enum AgentMode { DISABLED, OBSERVE, CONTROL }
    private AgentMode mode = AgentMode.DISABLED;

    // Single unified advanced PathNavigator
    private final PathNavigator pathNavigator = new PathNavigator();

    // Async pipeline
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoxelMind-Agent");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean inFlight = false;
    private final AtomicReference<ActionSchema.Actions> pending = new AtomicReference<>();
    private long lastErrorTick = 0;

    // Anti-spam tracking
    private String lastToAllMsg = null;
    private long lastToAllTick = -10000;
    private final Deque<HistoryEntry> recentAIChat = new ArrayDeque<>();
    private record HistoryEntry(String msg, long tick) {}

    // Conversation history
    private record ConversationEntry(String role, String sender, String text, long tick) {}
    private final Deque<ConversationEntry> conversation = new ArrayDeque<>();

    // Targeting state
    private ActionSchema.Target activeTarget = null;
    private Optional<BlockPos> cachedBlockPos = Optional.empty();

    private final AtomicInteger tickCounter = new AtomicInteger();
    private long lastAutoReplyTriggerTick = -10000;

    // Ticks for gating decisions
    private long lastConversationChangeTick = -1; // last tick when user/other/ai appended (we key on user/other for decision gating)
    private long lastConversationUsedForDecisionTick = -1; // last convo change tick consumed by a decision

    private boolean observeNeedsInitialDecision = false; // new flag

    public AIAgentController() { this.agent = createAgentFromConfig(); }

    private static AgentClient createAgentFromConfig() {
        String url = Config.get().agentUrl();
        if (url == null || url.trim().isEmpty()) return new StubAgentClient();
        return new HttpAgentClient();
    }

    public AgentMode getMode() { return mode; }
    public void setMode(AgentMode newMode) {
        if (newMode == null) newMode = AgentMode.DISABLED;
        if (this.mode == newMode) return;
        AgentMode prev = this.mode;
        this.mode = newMode;
        // If leaving CONTROL, ensure inputs released
        if (prev == AgentMode.CONTROL && newMode != AgentMode.CONTROL) input.resetAll();
        // If entering CONTROL fresh, also clear pending navigation state if any inconsistent (optional)
        if (newMode != AgentMode.CONTROL) {
            // In passive modes we should not carry navigation or target locks that might auto-aim
            pathNavigator.cancel();
            activeTarget = null;
            cachedBlockPos = Optional.empty();
        }
        if (newMode == AgentMode.OBSERVE) {
            // Mark that we should run at least one immediate decision even with empty conversation
            observeNeedsInitialDecision = true;
            try { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[VoxelMind] Observe mode enabled. Waiting for chat or interval.")); } catch (Exception ignored) {}
        }
    }

    // Public user injection API
    public void sayToAI(String text) { if (text != null && !text.isBlank()) addConversation("user", getLocalPlayerNameSafe(), text.trim()); }

    public String getConversationSnapshot(int maxLines) {
        if (maxLines <= 0) return "";
        StringBuilder sb = new StringBuilder();
        int size = conversation.size();
        int start = Math.max(0, size - maxLines);
        int idx = 0;
        for (ConversationEntry e : conversation) {
            if (idx++ < start) continue;
            sb.append(e.role()); if (e.sender() != null) sb.append(':').append(e.sender()); sb.append(':').append(' ').append(e.text()).append('\n');
        }
        return sb.toString();
    }
    public void clearConversation() { conversation.clear(); }

    private void addConversation(String role, String sender, String text) {
        String trimmed = text == null ? "" : text.trim(); if (trimmed.isEmpty()) return;
        long now = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
        conversation.addLast(new ConversationEntry(role, sender, trimmed, now));
        // Mark conversation change only for human (user/other) messages to gate OBSERVE decisions
        if (!role.equals("ai")) lastConversationChangeTick = now;
        pruneConversation(now);
    }

    private void pruneConversation(long nowTick) {
        int limit = Config.get().aiConversationLimit();
        while (conversation.size() > limit) conversation.pollFirst();
    }

    private String getLocalPlayerNameSafe() {
        try { var mc = Minecraft.getInstance(); if (mc.player != null) return mc.player.getGameProfile().getName(); } catch (Exception ignored) {}
        return "player";
    }

    private String buildConversationContext() {
        if (conversation.isEmpty()) {
            if (Minecraft.getInstance().player != null && Config.get().observeAnswerOnly() && mode == AgentMode.OBSERVE)
                return "AnswerOnlyMode: true";
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (Config.get().observeAnswerOnly() && mode == AgentMode.OBSERVE) sb.append("AnswerOnlyMode: true\n");
        sb.append("Conversation (most recent last):\n");
        for (ConversationEntry e : conversation) {
            sb.append(e.role()).append(':');
            if (e.sender() != null) sb.append(e.sender()).append(':');
            sb.append(' ').append(e.text()).append('\n');
        }
        return sb.toString();
    }

    public void onClientTick(Minecraft mc) {
        if (mode == AgentMode.DISABLED) return; if (mc == null || mc.level == null || mc.player == null) return;
        if (Config.get().debug()) {
            int tc = tickCounter.get();
            if (tc % 200 == 0) {
                LOGGER.info("[VoxelMind] Tick heartbeat mode={} inFlight={} convoSize={} observeInitFlag={}", mode, inFlight, conversation.size(), observeNeedsInitialDecision);
            }
        }
        // ...existing code before agent swap...
        AgentClient desired = createAgentFromConfig();
        if (!desired.getClass().equals(this.agent.getClass())) { this.agent = desired; mc.gui.getChat().addMessage(Component.literal("[VoxelMind] Agent switched to " + desired.getClass().getSimpleName())); }
        ActionSchema.Actions ready = pending.getAndSet(null); if (ready != null) applyActions(mc, ready);
        int interval = Config.get().decisionIntervalTicks();
        int t = tickCounter.incrementAndGet(); boolean runNow = (t % Math.max(1, interval)) == 0;
        if (mode == AgentMode.OBSERVE) {
            if (observeNeedsInitialDecision) { runNow = true; }
            else {
                // Only suppress when there is conversation but no new human input since last decision
                if (!conversation.isEmpty() && lastConversationChangeTick <= lastConversationUsedForDecisionTick) runNow = false;
            }
        }
        if (runNow && !inFlight) { inFlight = true; ScreenshotUtil.captureAsync(mc, png -> {
            if (png == null) { inFlight = false; maybeSayOnce(mc, "Screenshot capture failed (null)"); observeNeedsInitialDecision = false; return; }
            if (mode == AgentMode.OBSERVE) { lastConversationUsedForDecisionTick = lastConversationChangeTick; observeNeedsInitialDecision = false; }
            AgentClient.GameContext ctx = buildContext(mc); String ctxText = buildConversationContext(); Optional<String> convoOpt = ctxText.isBlank()? Optional.empty(): Optional.of(ctxText);
            exec.submit(() -> { try { var actions = agent.decide(png, ctx, convoOpt); if (actions != null) pending.set(actions);} catch (Throwable th){ maybeSayOnce(mc, "Agent request failed"); } finally { inFlight = false; }});
        }); }
        if (mode == AgentMode.CONTROL) input.tick(mc);
        if (mode == AgentMode.CONTROL && pathNavigator.isActive()) { var step = pathNavigator.produceStep(mc.player); if (step != null) { step.view().ifPresent(v -> input.applyView(mc,v)); input.applyMovement(mc, step.movement()); }}
        if (mode == AgentMode.CONTROL && activeTarget != null) autoAimAtActiveTarget(mc);
    }

    public void triggerDecisionNow(Minecraft mc) {
        if (mode == AgentMode.DISABLED) return; if (mc == null || mc.level == null || mc.player == null || inFlight) return;
        inFlight = true;
        ScreenshotUtil.captureAsync(mc, png -> {
            if (png == null) { inFlight = false; maybeSayOnce(mc, "Screenshot capture failed (null)"); return; }
            AgentClient.GameContext ctx = buildContext(mc); String ctxText = buildConversationContext(); Optional<String> convoOpt = ctxText.isBlank()? Optional.empty(): Optional.of(ctxText);
            exec.submit(() -> { try { var actions = agent.decide(png, ctx, convoOpt); if (actions != null) pending.set(actions);} catch (Throwable th){ maybeSayOnce(mc, "Agent request failed"); } finally { inFlight = false; }});
        });
    }

    private void maybeSayOnce(Minecraft mc, String msg) { long now = mc.level.getGameTime(); if (now - lastErrorTick > 200) { lastErrorTick = now; mc.execute(() -> mc.gui.getChat().addMessage(Component.literal("[VoxelMind] " + msg))); } }

    private static AgentClient.GameContext buildContext(Minecraft mc) {
        LocalPlayer p = mc.player; String dim = mc.level.dimension().location().toString(); String biome = mc.level.getBiome(p.blockPosition()).unwrapKey().map(k->k.location().toString()).orElse("?");
        return new AgentClient.GameContext(dim, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), biome);
    }

    public void applyActions(Minecraft mc, ActionSchema.Actions actions) {
        Objects.requireNonNull(actions);
        // Unified chat handling with simple anti-spam (skip identical message within 200 ticks ~10s)
        actions.chat().message().ifPresent(msg -> { if (msg != null) {
            String trimmed = msg.trim();
            if (!trimmed.isEmpty()) {
                // Sanitize to avoid illegal chat character disconnects
                ChatSanitizer.Result res = ChatSanitizer.sanitize(trimmed);
                String safe = res.sanitized();
                if (!safe.isEmpty()) {
                    long now = mc.level.getGameTime();
                    if (shouldAllowAIChat(safe, now)) {
                        boolean publicChat = Config.get().allowPublicChat();
                        boolean prefix = Config.get().showAIPrefix();
                        String displayBody = safe; // body without prefix for dedup & convo
                        String display = prefix ? "[AI] " + displayBody : displayBody;
                        if (publicChat && mc.getConnection()!=null) mc.getConnection().sendChat(display); else mc.gui.getChat().addMessage(Component.literal(display));
                        lastToAllMsg = displayBody; lastToAllTick = now; recentAIChat.addLast(new HistoryEntry(displayBody, now)); pruneRecent(now); addConversation("ai", null, displayBody);
                        // Previously printed sanitized diff to chat; now log only when debug enabled
                        if (res.changed() && Config.get().debug()) {
                            LOGGER.info("[VoxelMind] (Sanitized AI chat: removed {})", res.removedCount());
                        }
                    }
                }
            }
        }});
        // IGNORE model-provided view (we rely on auto-lock). Comment out previous direct application.
        // input.applyView(mc, actions.view());

        // Navigation only when in CONTROL mode
        if (mode == AgentMode.CONTROL && actions.navigation().hasRequest() && mc.player != null) { pathNavigator.cancel(); pathNavigator.start(mc.player, actions.navigation()); }
        // Update active target only in CONTROL
        if (mode == AgentMode.CONTROL && actions.target().isPresent()) { var tgt = actions.target().get(); if (!tgt.hasBlock() && !tgt.hasEntity()) { activeTarget = null; cachedBlockPos = Optional.empty(); } else { activeTarget = tgt; cachedBlockPos = Optional.empty(); } }
        if (mode == AgentMode.CONTROL && activeTarget != null) autoAimAtActiveTarget(mc);
        // Movement / mouse application only if CONTROL
        if (mode == AgentMode.CONTROL) { ActionSchema.Movement mv = ActionSchema.Movement.none(); if (pathNavigator.isActive() && mc.player != null) { var step = pathNavigator.produceStep(mc.player); if (step != null) { step.view().ifPresent(v -> input.applyView(mc,v)); mv = step.movement(); } }
            ActionSchema.Mouse mouse = actions.mouse(); var crossOpt = CrosshairUtil.getCrosshairInfo(mc); boolean crossOnTarget = false; boolean crossOnLog = false; double crossDist = Double.MAX_VALUE; if (crossOpt.isPresent()) { crossDist = crossOpt.get().distance(); crossOnLog = crossOpt.get().isLog(); if (activeTarget != null) crossOnTarget = TargetingUtil.crosshairMatchesTarget(mc, activeTarget, crossOpt.get()); }
            if (Config.get().assistOnlyPrimaryWhenAiming()) { boolean allowPrimary; if (activeTarget != null) allowPrimary = crossOnTarget && crossDist <= Config.get().assistPrimaryReachDistance(); else allowPrimary = crossOnLog && crossDist <= Config.get().assistPrimaryReachDistance(); boolean attackDown = mc.options.keyAttack.isDown(); var left = mouse.left(); if (!allowPrimary) { if (attackDown || left == ActionSchema.PressType.HOLD || left == ActionSchema.PressType.TAP) left = ActionSchema.PressType.RELEASE; else left = ActionSchema.PressType.NONE; mouse = new ActionSchema.Mouse(left, mouse.right()); } }
            input.applyMovement(mc, mv); input.applyMouse(mc, mouse); }
    }

    private long getLastHumanConversationTick() {
        for (var it = conversation.descendingIterator(); it.hasNext();) {
            ConversationEntry e = it.next();
            if (!e.role().equals("ai")) return e.tick();
        }
        return -1;
    }

    private boolean shouldAllowAIChat(String msg, long nowTick) {
        Config cfg = Config.get(); int dedupWindow = cfg.aiChatDedupTicks(); int minInterval = cfg.aiChatMinIntervalTicks(); pruneRecent(nowTick);
        if (cfg.aiNoRepeatConsecutive() && lastToAllMsg != null && msg.equals(lastToAllMsg)) {
            long lastHuman = getLastHumanConversationTick();
            if (lastHuman <= lastToAllTick) return false; // no new human input since last identical AI message
        }
        if (!recentAIChat.isEmpty()) { var last = recentAIChat.peekLast(); if (last != null && nowTick - last.tick < minInterval) return false; }
        for (HistoryEntry e : recentAIChat) { if (e.msg.equals(msg) && nowTick - e.tick < dedupWindow) return false; }
        return true;
    }

    private void pruneRecent(long nowTick) {
        Config cfg = Config.get(); int dedupWindow = cfg.aiChatDedupTicks(); int limit = cfg.aiChatRecentLimit(); Iterator<HistoryEntry> it = recentAIChat.iterator(); while (it.hasNext()) { HistoryEntry e = it.next(); if (nowTick - e.tick > dedupWindow * 2L) it.remove(); } while (recentAIChat.size() > limit) recentAIChat.pollFirst(); }

    private void autoAimAtActiveTarget(Minecraft mc) {
        // If advanced navigator mid-jump (jump flag active) and no explicit target, allow path view dominance
        if (mc == null || mc.player == null || activeTarget == null) return;
        int radius = Config.get().targetLockRadius();
        // Prefer entity targeting
        if (activeTarget.hasEntity()) { TargetingUtil.resolveTargetEntity(mc, activeTarget, radius).flatMap(e -> TargetingUtil.computeViewToEntity(mc, e)).ifPresent(v -> input.applyView(mc,v)); return; }
        // Block / pos targeting
        if (activeTarget.hasBlock()) { if (activeTarget.hasPos()) { cachedBlockPos = Optional.of(new BlockPos(activeTarget.x().orElse(0), activeTarget.y().orElse(0), activeTarget.z().orElse(0))); } else { boolean needResolve = cachedBlockPos.isEmpty(); if (!needResolve) { var cp = cachedBlockPos.get(); double dist2 = cp.distToCenterSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ()); boolean tooFar = dist2 > (radius + 2)*(radius + 2); boolean notVisible = !TargetingUtil.isBlockVisible(mc, cp); boolean replaced = mc.level.getBlockState(cp).isAir(); if (tooFar || notVisible || replaced) needResolve = true; } if (needResolve) cachedBlockPos = TargetingUtil.resolveTargetBlockPreferVisible(mc, activeTarget, radius); } cachedBlockPos.flatMap(p -> TargetingUtil.computeViewToPos(mc,p)).ifPresent(v -> input.applyView(mc,v)); }
    }

    private boolean isOwnRecentAIChat(String text, long nowTick) {
        if (text == null || text.isEmpty()) return false;
        int window = Config.get().aiLocalEchoWindowTicks();
        if (recentAIChat.isEmpty()) return false;
        for (var it = recentAIChat.descendingIterator(); it.hasNext(); ) {
            HistoryEntry e = it.next();
            long age = nowTick - e.tick;
            if (age > window) break;
            if (e.msg.equals(text)) return true;
        }
        return false;
    }

    public void onPlayerChat(String sender, String rawText, boolean isLocal) {
        if (rawText == null) return; String text = rawText.trim(); if (text.isEmpty()) return; if (text.startsWith("[AI]")) return;
        long now = 0; try { var mc = Minecraft.getInstance(); if (mc.level != null) now = mc.level.getGameTime(); } catch (Exception ignored) {}
        if (isLocal && isOwnRecentAIChat(text, now)) {
            if (Config.get().debug()) LOGGER.debug("Ignoring own AI message echoed in local chat: {}", text);
            return; // Do not treat as user conversation input
        }
        addConversation(isLocal? "user":"other", sender, text);
        if (Config.get().aiAutoReply() && mode != AgentMode.DISABLED) {
            Minecraft mc = Minecraft.getInstance(); long nowTick = mc.level == null ? 0 : mc.level.getGameTime(); int interval = Math.max(Config.get().aiChatMinIntervalTicks()*2, 40);
            if (nowTick - lastAutoReplyTriggerTick >= interval) { if (shouldAutoReply(text, isLocal)) { lastAutoReplyTriggerTick = nowTick; triggerDecisionNow(mc); } }
        }
    }

    private boolean shouldAutoReply(String msg, boolean isLocal) {
        if (Config.get().autoReplyLoose()) return true; String lower = msg.toLowerCase(); if (lower.contains("[ai]")) return false;
        String[] keys = {"ai","bot","你是谁","帮助","helper","assist","助手"}; for (String k : keys) if (lower.contains(k)) return true; if (isLocal && mode == AgentMode.CONTROL) return true; return false;
    }
}
