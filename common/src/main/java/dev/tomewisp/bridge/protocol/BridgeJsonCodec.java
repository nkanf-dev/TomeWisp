package dev.tomewisp.bridge.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Set;

public final class BridgeJsonCodec {
    private static final Map<Class<?>, Set<String>> FIELDS = Map.of(
            CapabilityPayload.class, Set.of(
                    "version", "remoteTools", "serverModel",
                    "serverContextWindowTokens", "serverMaxOutputTokens",
                    "serverPromptAndToolTokens", "serverCanonicalModelId"),
            RemoteToolCallPayload.class,
                    Set.of("version", "correlationId", "sessionId", "toolId", "argumentsJson"),
            RemoteToolResultChunkPayload.class,
                    Set.of("version", "correlationId", "index", "total", "contentHash", "base64Data"),
            RemoteCancelPayload.class, Set.of("version", "correlationId"),
            ServerAgentRequestPayload.class,
                    Set.of("version", "requestId", "sessionId", "question", "stream", "history"),
            ServerAgentRequestChunkPayload.class,
                    Set.of("version", "requestId", "index", "total", "contentHash", "base64Data"),
            ServerAgentCancelPayload.class, Set.of("version", "requestId"),
            ServerAgentEventPayload.class,
                    Set.of("version", "requestId", "eventType", "eventJson", "terminal"));

    private final Gson gson;

    public BridgeJsonCodec() {
        this(new Gson());
    }

    public BridgeJsonCodec(Gson gson) {
        this.gson = gson;
    }

    public String encode(Object payload) {
        if (!FIELDS.containsKey(payload.getClass())) {
            throw new IllegalArgumentException("Unsupported bridge payload " + payload.getClass().getName());
        }
        return gson.toJson(payload);
    }

    public <T> T decode(String json, Class<T> type) {
        Set<String> expected = FIELDS.get(type);
        if (expected == null) {
            throw new IllegalArgumentException("Unsupported bridge payload " + type.getName());
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Bridge payload must be a JSON object");
        }
        JsonObject object = parsed.getAsJsonObject();
        if (!object.keySet().equals(expected)) {
            Set<String> missing = new java.util.TreeSet<>(expected);
            missing.removeAll(object.keySet());
            Set<String> extra = new java.util.TreeSet<>(object.keySet());
            extra.removeAll(expected);
            throw new IllegalArgumentException(
                    "Bridge payload schema mismatch; missing=" + missing + ", extra=" + extra);
        }
        if (type == ServerAgentRequestPayload.class) {
            JsonElement history = object.get("history");
            if (history == null || !history.isJsonArray()) {
                throw new IllegalArgumentException("Server Agent history must be an array");
            }
            for (JsonElement item : history.getAsJsonArray()) {
                if (!item.isJsonObject()
                        || !item.getAsJsonObject().keySet().equals(Set.of("role", "content"))) {
                    throw new IllegalArgumentException("Server Agent history schema mismatch");
                }
                JsonElement content = item.getAsJsonObject().get("content");
                if (content == null || !content.isJsonArray()) {
                    throw new IllegalArgumentException("Server Agent history content must be an array");
                }
                for (JsonElement block : content.getAsJsonArray()) {
                    if (!block.isJsonObject()) {
                        throw new IllegalArgumentException(
                                "Server Agent history content schema mismatch");
                    }
                    JsonObject contentObject = block.getAsJsonObject();
                    JsonElement kind = contentObject.get("kind");
                    Set<String> contentFields = kind == null ? Set.of() : switch (kind.getAsString()) {
                        case "TEXT" -> Set.of("kind", "text");
                        case "TOOL_USE" -> Set.of(
                                "kind", "toolUseId", "toolName", "json");
                        case "TOOL_RESULT" -> Set.of(
                                "kind", "toolUseId", "json", "error");
                        default -> Set.of();
                    };
                    if (!contentObject.keySet().equals(contentFields)) {
                        throw new IllegalArgumentException(
                                "Server Agent history content schema mismatch");
                    }
                }
            }
        }
        T value = gson.fromJson(object, type);
        if (value == null) {
            throw new IllegalArgumentException("Bridge payload decoded to null");
        }
        return value;
    }
}
