package dev.openallay.model.anthropic;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRequest;
import dev.openallay.model.ModelRole;
import dev.openallay.model.ModelToolDefinition;
import dev.openallay.model.ModelTurn;
import dev.openallay.model.ModelUsage;
import dev.openallay.model.config.ModelConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class AnthropicJsonCodec {
    private final Gson gson;

    public AnthropicJsonCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public String requestBody(ModelConfig config, ModelRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.model());
        root.addProperty("max_tokens", config.maxOutputTokens());
        root.addProperty("system", request.systemPrompt());
        root.addProperty("stream", request.stream());
        JsonArray messages = new JsonArray();
        for (ModelMessage message : request.messages()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("role", message.role() == ModelRole.USER ? "user" : "assistant");
            JsonArray content = new JsonArray();
            for (ModelContent block : message.content()) {
                content.add(encodeContent(block));
            }
            encoded.add("content", content);
            messages.add(encoded);
        }
        root.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ModelToolDefinition tool : request.tools()) {
                JsonObject encoded = new JsonObject();
                encoded.addProperty("name", tool.name());
                encoded.addProperty("description", tool.description());
                encoded.add("input_schema", tool.inputSchema());
                tools.add(encoded);
            }
            root.add("tools", tools);
        }
        return gson.toJson(root);
    }

    public ModelTurn parseTurn(String json, Consumer<ModelEvent> events) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("error") && !root.get("error").isJsonNull()) {
            throw new IllegalArgumentException("Anthropic response contains an error object");
        }
        String model = requiredString(root, "model");
        String stopReason = requiredString(root, "stop_reason");
        List<ModelContent> content = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("content")) {
            ModelContent block = decodeContent(element.getAsJsonObject());
            content.add(block);
            emitCompleteBlock(block, events);
        }
        ModelUsage usage = parseUsage(root.getAsJsonObject("usage"));
        events.accept(new ModelEvent.UsageUpdate(usage));
        events.accept(new ModelEvent.MessageComplete(stopReason));
        return new ModelTurn("anthropic_messages", model, content, stopReason, usage);
    }

    public ModelUsage parseUsage(JsonObject object) {
        if (object == null) {
            return ModelUsage.empty();
        }
        return new ModelUsage(
                longValue(object, "input_tokens"),
                longValue(object, "output_tokens"),
                longValue(object, "cache_read_input_tokens"));
    }

    private JsonObject encodeContent(ModelContent block) {
        JsonObject encoded = new JsonObject();
        switch (block) {
            case ModelContent.Text text -> {
                encoded.addProperty("type", "text");
                encoded.addProperty("text", text.text());
            }
            case ModelContent.Reasoning reasoning -> {
                encoded.addProperty("type", "thinking");
                encoded.addProperty("thinking", reasoning.text());
                if (reasoning.signature() != null) {
                    encoded.addProperty("signature", reasoning.signature());
                }
            }
            case ModelContent.ToolUse toolUse -> {
                encoded.addProperty("type", "tool_use");
                encoded.addProperty("id", toolUse.id());
                encoded.addProperty("name", toolUse.name());
                encoded.add("input", toolUse.input());
            }
            case ModelContent.ToolResult result -> {
                encoded.addProperty("type", "tool_result");
                encoded.addProperty("tool_use_id", result.toolUseId());
                encoded.addProperty("content", result.text());
                encoded.addProperty("is_error", result.error());
            }
        }
        return encoded;
    }

    private ModelContent decodeContent(JsonObject object) {
        return switch (requiredString(object, "type")) {
            case "text" -> new ModelContent.Text(requiredString(object, "text"));
            case "thinking" -> new ModelContent.Reasoning(
                    requiredString(object, "thinking"),
                    object.has("signature") ? object.get("signature").getAsString() : null);
            case "tool_use" -> new ModelContent.ToolUse(
                    requiredString(object, "id"),
                    requiredString(object, "name"),
                    object.getAsJsonObject("input"));
            default -> throw new IllegalArgumentException(
                    "Unsupported Anthropic content type: " + requiredString(object, "type"));
        };
    }

    private static void emitCompleteBlock(ModelContent block, Consumer<ModelEvent> events) {
        switch (block) {
            case ModelContent.Text text -> events.accept(new ModelEvent.TextDelta(text.text()));
            case ModelContent.Reasoning reasoning ->
                events.accept(new ModelEvent.ReasoningDelta(reasoning.text()));
            case ModelContent.ToolUse toolUse -> events.accept(new ModelEvent.ToolUseComplete(
                    toolUse.id(), toolUse.name(), toolUse.input()));
            case ModelContent.ToolResult ignored -> {}
        }
    }

    private static String requiredString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing Anthropic response field: " + field);
        }
        return object.get(field).getAsString();
    }

    private static long longValue(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsLong()
                : 0;
    }
}
