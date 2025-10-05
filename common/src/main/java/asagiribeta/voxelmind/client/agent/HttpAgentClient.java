package asagiribeta.voxelmind.client.agent;

import asagiribeta.voxelmind.config.Config;
import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * HTTP agent using an OpenAI-compatible chat.completions API.
 * Sends screenshot as image_url (data URI) with a JSON-only system instruction,
 * expects the assistant content to be a single JSON object following our Actions schema.
 */
public final class HttpAgentClient implements AgentClient {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1500))
            .build();
    private final Gson gson = new GsonBuilder().create();
    private static final Logger LOGGER = LogManager.getLogger("VoxelMind-AI");

    private static final String SYSTEM_INSTRUCTION = """
        You are a Minecraft AI agent AND in-game assistant.
        Always output ONLY a single strict JSON object with keys: chat, navigation, mouse, target (omit view unless critical). No markdown or extra text.
        If the provided context contains a line starting with 'AnswerOnlyMode: true' then you MUST:
          - Only answer as chat.message (or null if no reply needed)
          - Set navigation.dx/dy/dz = null, target fields = null, mouse.left/right = \"NONE\"
        Conversation context, if present, will follow the marker line:
        Conversation (most recent last):\n
        Each line format:
          user:PlayerName: message
          other:OtherPlayer: message
          ai: message
        Guidelines:
        - Keep replies concise (<120 chars) and mirror the language of the most recent human line (Chinese vs English) unless responding requires otherwise.
        - Avoid repeating earlier AI messages; only add new info, progress updates, or direct answers.
        - navigation.{dx,dy,dz} are SMALL relative integer moves (-5..5). Omit/null when no movement needed.
        - Only one targeting strategy at a time: coordinates OR blockId/blockTag OR entityType/entityName.
        - If no action is needed, navigation & target should be null and mouse NONE.
        - mouse.left/right: NONE|TAP|HOLD|RELEASE. Use TAP for one-shot interactions; HOLD only if sustained.
        - Never invent entities/blocks you cannot infer from typical player context.
        - NEVER output extra keys or explanations. Return valid JSON only.
        Anti-repetition / silence rules:
        - If there has been NO new human (user/other) message since your last reply and you have no meaningful progress or status update, set chat to null instead of sending an acknowledgement.
        - DO NOT send generic acknowledgements like \"好的，我明白了。\" / \"好的\" / \"明白\" repeatedly.
        - Never repeat exactly the same chat text you already sent earlier unless the user explicitly asks you to confirm or you are correcting an earlier mistake.
        - Prefer staying silent (chat null) over filler text.
    """;

    @Override
    public ActionSchema.Actions decide(byte[] screenshotPngBytes, GameContext context, java.util.Optional<String> userGoal) {
        String rawUrl = Config.get().agentUrl();
        if (rawUrl == null || rawUrl.isEmpty()) return ActionSchema.Actions.none();
        String url = normalizeEndpoint(rawUrl);
        try {
            HttpRequest request = buildOpenAIStyleRequest(url, screenshotPngBytes, context, userGoal);
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                if (Config.get().debug()) {
                    String body = resp.body();
                    if (body != null && body.length() > 200) body = body.substring(0, 200) + "...";
                    return debugSelf("HTTP " + resp.statusCode() + " from " + url + (body == null || body.isEmpty() ? "" : ": " + body));
                }
                return ActionSchema.Actions.none();
            }
            String content = null;
            JsonElement je = JsonParser.parseString(resp.body());
            if (je.isJsonObject()) {
                JsonObject obj = je.getAsJsonObject();
                if (obj.has("choices") && obj.get("choices").isJsonArray() && obj.getAsJsonArray("choices").size() > 0) {
                    JsonObject choice0 = obj.getAsJsonArray("choices").get(0).getAsJsonObject();
                    JsonObject msg = choice0.has("message") && choice0.get("message").isJsonObject()
                            ? choice0.getAsJsonObject("message") : null;
                    if (msg != null && msg.has("content")) {
                        content = msg.get("content").getAsString();
                    }
                }
            }
            if (content == null) {
                if (Config.get().debug()) {
                    return debugSelf("No content in response from " + url);
                }
                return ActionSchema.Actions.none();
            }
            if (Config.get().debug()) {
                String trimmed = content.length() > 1000 ? content.substring(0,1000) + "..." : content;
                LOGGER.info("[VoxelMind][AI raw] {}", trimmed);
            }
            try {
                JsonElement parsedJson = JsonParser.parseString(content);
                ActionSchema.Actions actions = parseActionsFlexible(parsedJson);
                if (Config.get().debug()) {
                    // Re-serialize normalized actions summary
                    JsonObject summary = new JsonObject();
                    JsonObject nav = new JsonObject();
                    addOptional(nav, "dx", actions.navigation().dx());
                    addOptional(nav, "dy", actions.navigation().dy());
                    addOptional(nav, "dz", actions.navigation().dz());
                    JsonObject tgt = new JsonObject();
                    if (actions.target().isPresent()) {
                        var t = actions.target().get();
                        tgt.addProperty("hasPos", t.hasPos());
                        addOptional(tgt, "blockId", t.blockId());
                        addOptional(tgt, "blockTag", t.blockTag());
                        addOptional(tgt, "entityType", t.entityType());
                        addOptional(tgt, "entityName", t.entityName());
                    } else {
                        tgt.addProperty("hasPos", false);
                        tgt.add("blockId", JsonNull.INSTANCE);
                        tgt.add("blockTag", JsonNull.INSTANCE);
                        tgt.add("entityType", JsonNull.INSTANCE);
                        tgt.add("entityName", JsonNull.INSTANCE);
                    }
                    JsonObject mouse = new JsonObject();
                    mouse.addProperty("left", actions.mouse().left().name());
                    mouse.addProperty("right", actions.mouse().right().name());
                    JsonObject chat = new JsonObject();
                    addOptional(chat, "message", actions.chat().message());
                    summary.add("navigation", nav);
                    summary.add("target", tgt);
                    summary.add("mouse", mouse);
                    summary.add("chat", chat);
                    LOGGER.info("[VoxelMind][AI parsed] {}", summary.toString());
                }
                return actions;
            } catch (Exception e) {
                String extracted = extractFirstJsonObject(content);
                if (extracted != null) {
                    try { return parseActionsFlexible(JsonParser.parseString(extracted)); } catch (Exception ignored) {}
                }
                if (Config.get().debug()) {
                    String snippet = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                    return debugSelf("Parse error for content: " + snippet);
                }
                return ActionSchema.Actions.none();
            }
        } catch (Exception e) {
            if (Config.get().debug()) {
                return debugSelf("Request error to " + url + ": " + e.getClass().getSimpleName());
            }
            return ActionSchema.Actions.none();
        }
    }

    private static String normalizeEndpoint(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith("/v1") || lower.endsWith("/v1/")) {
            return url.endsWith("/") ? url + "chat/completions" : url + "/chat/completions";
        }
        return url;
    }

    // Modified: now logs instead of returning a chat message so debug info stays out of player chat.
    private static ActionSchema.Actions debugSelf(String msg) {
        LOGGER.info("[VoxelMind][AI debug] {}", msg);
        return ActionSchema.Actions.none();
    }

    private static String extractFirstJsonObject(String s) {
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) return s.substring(start, end + 1);
        return null;
    }

    private HttpRequest buildOpenAIStyleRequest(String url, byte[] png, GameContext context, java.util.Optional<String> userGoal) {
        String model = Config.get().model().isEmpty() ? "gpt-4o-mini" : Config.get().model();
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.add("response_format", jsonObject("type", new JsonPrimitive("json_object")));
        root.addProperty("temperature", 0);

        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", SYSTEM_INSTRUCTION);
        messages.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject partText = new JsonObject();
        partText.addProperty("type", "text");
        partText.addProperty("text", buildUserPrompt(context, userGoal));
        content.add(partText);
        JsonObject partImage = new JsonObject();
        partImage.addProperty("type", "image_url");
        JsonObject imageObj = new JsonObject();
        imageObj.addProperty("url", dataUri);
        partImage.add("image_url", imageObj);
        content.add(partImage);
        user.add("content", content);
        messages.add(user);

        root.add("messages", messages);

        HttpRequest.Builder rb = baseRequest(url)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(root), StandardCharsets.UTF_8));
        return rb.build();
    }

    private static String buildUserPrompt(GameContext c, java.util.Optional<String> extraContext) {
        String base = "World: dimension=" + c.dimension() + ", biome=" + c.biome() +
                ", pos=(" + c.x() + "," + c.y() + "," + c.z() + ") yaw=" + c.yaw() + ", pitch=" + c.pitch() + ".";
        String convo = extraContext.map(s -> "\n" + s).orElse("");
        return base + convo + "\nDecide next JSON Actions now.";
    }

    private HttpRequest.Builder baseRequest(String url) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, Config.get().decisionIntervalTicks() * 400L)))
                .header("Content-Type", "application/json");
        String key = Config.get().apiKey();
        if (key != null && !key.isEmpty()) rb.header("Authorization", "Bearer " + key);
        return rb;
    }

    private static JsonObject jsonObject(String k, JsonElement v) { JsonObject o = new JsonObject(); o.add(k, v); return o; }

    // New flexible parser that tolerates simplified forms produced by the model.
    private ActionSchema.Actions parseActionsFlexible(JsonElement je) {
        if (!(je instanceof JsonObject root)) return ActionSchema.Actions.none();
        // chat may be object {message:".."} OR primitive string
        ActionSchema.Chat chat = parseChatFlexible(root.get("chat"));
        ActionSchema.Navigation nav = parseNavigationFlexible(root.get("navigation"));
        ActionSchema.View view = parseViewFlexible(root.get("view"));
        ActionSchema.Mouse mouse = parseMouseFlexible(root.get("mouse"));
        Optional<ActionSchema.Target> target = parseTargetFlexible(root.get("target"));
        return new ActionSchema.Actions(chat, nav, view, mouse, target);
    }

    private static ActionSchema.Chat parseChatFlexible(JsonElement el) {
        if (el == null || el.isJsonNull()) return ActionSchema.Chat.none();
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("message") && !o.get("message").isJsonNull()) {
                String m = o.get("message").getAsString();
                if (m != null && !m.isBlank()) return new ActionSchema.Chat(Optional.of(m.trim()));
            }
            return ActionSchema.Chat.none();
        }
        if (el.isJsonPrimitive()) {
            try {
                String m = el.getAsString();
                if (m != null && !m.isBlank()) return new ActionSchema.Chat(Optional.of(m.trim()));
            } catch (Exception ignored) {}
        }
        return ActionSchema.Chat.none();
    }

    private static ActionSchema.Navigation parseNavigationFlexible(JsonElement el) {
        if (el == null || el.isJsonNull()) return ActionSchema.Navigation.none();
        if (!el.isJsonObject()) return ActionSchema.Navigation.none();
        JsonObject o = el.getAsJsonObject();
        Optional<Integer> dx = o.has("dx") && !o.get("dx").isJsonNull() ? safeInt(o.get("dx")) : Optional.empty();
        Optional<Integer> dy = o.has("dy") && !o.get("dy").isJsonNull() ? safeInt(o.get("dy")) : Optional.empty();
        Optional<Integer> dz = o.has("dz") && !o.get("dz").isJsonNull() ? safeInt(o.get("dz")) : Optional.empty();
        return new ActionSchema.Navigation(dx, dy, dz);
    }

    private static Optional<Integer> safeInt(JsonElement e) {
        try {
            if (e != null && e.isJsonPrimitive()) {
                JsonPrimitive p = e.getAsJsonPrimitive();
                if (p.isNumber()) return Optional.of((int)Math.round(p.getAsDouble()));
                if (p.isString()) return Optional.of(Integer.parseInt(p.getAsString().trim()));
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static ActionSchema.View parseViewFlexible(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonObject()) return ActionSchema.View.none();
        JsonObject o = el.getAsJsonObject();
        Optional<Float> yawAbs = optFloat(o, "yawAbs");
        Optional<Float> pitchAbs = optFloat(o, "pitchAbs");
        Optional<Float> yawDelta = optFloat(o, "yawDelta");
        Optional<Float> pitchDelta = optFloat(o, "pitchDelta");
        return new ActionSchema.View(yawAbs, pitchAbs, yawDelta, pitchDelta);
    }

    private static Optional<Float> optFloat(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return Optional.empty();
        try {
            JsonElement e = o.get(k);
            if (e.isJsonPrimitive()) {
                JsonPrimitive p = e.getAsJsonPrimitive();
                if (p.isNumber()) return Optional.of(p.getAsFloat());
                if (p.isString()) return Optional.of(Float.parseFloat(p.getAsString().trim()));
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static Optional<ActionSchema.Target> parseTargetFlexible(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonObject()) return Optional.empty();
        JsonObject o = el.getAsJsonObject();
        Optional<Integer> x = safeInt(o.get("x"));
        Optional<Integer> y = safeInt(o.get("y"));
        Optional<Integer> z = safeInt(o.get("z"));
        Optional<String> blockId = optString(o, "blockId");
        Optional<String> blockTag = optString(o, "blockTag");
        Optional<String> entityType = optString(o, "entityType");
        Optional<String> entityName = optString(o, "entityName");
        ActionSchema.Target tgt = new ActionSchema.Target(x,y,z,blockId,blockTag,entityType,entityName);
        return Optional.of(tgt);
    }

    private static Optional<String> optString(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) return Optional.empty();
        try {
            String s = o.get(k).getAsString();
            if (s != null) {
                String t = s.trim();
                if (!t.isEmpty()) return Optional.of(t);
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static ActionSchema.Mouse parseMouseFlexible(JsonElement el) {
        if (el == null || el.isJsonNull()) return ActionSchema.Mouse.none();
        if (el.isJsonPrimitive()) {
            // Accept single string shorthand e.g. "NONE"
            String s = el.getAsString();
            if (s != null && !s.isBlank()) {
                ActionSchema.PressType pt = parsePressString(s);
                return new ActionSchema.Mouse(pt, ActionSchema.PressType.NONE);
            }
            return ActionSchema.Mouse.none();
        }
        if (!el.isJsonObject()) return ActionSchema.Mouse.none();
        JsonObject o = el.getAsJsonObject();
        ActionSchema.PressType left = o.has("left") && !o.get("left").isJsonNull() ? parsePressString(o.get("left").getAsString()) : ActionSchema.PressType.NONE;
        ActionSchema.PressType right = o.has("right") && !o.get("right").isJsonNull() ? parsePressString(o.get("right").getAsString()) : ActionSchema.PressType.NONE;
        return new ActionSchema.Mouse(left, right);
    }

    private static ActionSchema.PressType parsePressString(String s) {
        if (s == null) return ActionSchema.PressType.NONE;
        try { return ActionSchema.PressType.valueOf(s.trim().toUpperCase()); } catch (Exception ignored) { return ActionSchema.PressType.NONE; }
    }

    private static void addOptional(JsonObject o, String k, java.util.Optional<?> opt) {
        if (opt == null || opt.isEmpty()) { o.add(k, JsonNull.INSTANCE); return; }
        Object v = opt.get();
        if (v instanceof Number n) o.addProperty(k, n);
        else if (v instanceof Boolean b) o.addProperty(k, b);
        else o.addProperty(k, v.toString());
    }
}
