package dev.openallay.model.openai;

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

public final class OpenAiJsonCodec {
    private final Gson gson;

    public OpenAiJsonCodec(Gson gson) {
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public String requestBody(ModelConfig config, ModelRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", config.model());
        root.addProperty("max_completion_tokens", config.maxOutputTokens());
        root.addProperty("stream", request.stream());
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", request.systemPrompt());
        messages.add(system);
        for (ModelMessage message : request.messages()) {
            encodeMessage(message, messages);
        }
        root.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ModelToolDefinition tool : request.tools()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name());
                function.addProperty("description", tool.description());
                function.add("parameters", tool.inputSchema());
                JsonObject encoded = new JsonObject();
                encoded.addProperty("type", "function");
                encoded.add("function", function);
                tools.add(encoded);
            }
            root.add("tools", tools);
        }
        return gson.toJson(root);
    }

    public ModelTurn parseTurn(String json, Consumer<ModelEvent> events) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String model = requiredString(root, "model");
        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        String stopReason = requiredString(choice, "finish_reason");
        JsonObject message = choice.getAsJsonObject("message");
        List<ModelContent> content = decodeAssistant(message, events);
        ModelUsage usage = parseUsage(root.getAsJsonObject("usage"));
        events.accept(new ModelEvent.UsageUpdate(usage));
        events.accept(new ModelEvent.MessageComplete(stopReason));
        return new ModelTurn("openai_chat", model, content, stopReason, usage);
    }

    private void encodeMessage(ModelMessage message, JsonArray output) {
        List<ModelContent.ToolResult> results = message.content().stream()
                .filter(ModelContent.ToolResult.class::isInstance)
                .map(ModelContent.ToolResult.class::cast)
                .toList();
        if (!results.isEmpty()) {
            for (ModelContent.ToolResult result : results) {
                JsonObject encoded = new JsonObject();
                encoded.addProperty("role", "tool");
                encoded.addProperty("tool_call_id", result.toolUseId());
                encoded.addProperty("content", gson.toJson(result.value()));
                output.add(encoded);
            }
            return;
        }

        JsonObject encoded = new JsonObject();
        encoded.addProperty("role", message.role() == ModelRole.USER ? "user" : "assistant");
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        JsonArray toolCalls = new JsonArray();
        for (ModelContent block : message.content()) {
            switch (block) {
                case ModelContent.Text value -> text.append(value.text());
                case ModelContent.Reasoning value -> reasoning.append(value.text());
                case ModelContent.ToolUse value -> toolCalls.add(encodeToolCall(value));
                case ModelContent.ToolResult ignored -> throw new IllegalStateException();
            }
        }
        encoded.addProperty("content", text.isEmpty() ? null : text.toString());
        if (!reasoning.isEmpty()) {
            encoded.addProperty("reasoning_content", reasoning.toString());
        }
        if (!toolCalls.isEmpty()) {
            encoded.add("tool_calls", toolCalls);
        }
        output.add(encoded);
    }

    private JsonObject encodeToolCall(ModelContent.ToolUse tool) {
        JsonObject function = new JsonObject();
        function.addProperty("name", tool.name());
        function.addProperty("arguments", gson.toJson(tool.input()));
        JsonObject result = new JsonObject();
        result.addProperty("id", tool.id());
        result.addProperty("type", "function");
        result.add("function", function);
        return result;
    }

    private List<ModelContent> decodeAssistant(JsonObject message, Consumer<ModelEvent> events) {
        List<ModelContent> content = new ArrayList<>();
        if (message.has("content") && !message.get("content").isJsonNull()) {
            ModelContent.Text text = new ModelContent.Text(message.get("content").getAsString());
            content.add(text);
            events.accept(new ModelEvent.TextDelta(text.text()));
        }
        if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
            ModelContent.Reasoning reasoning =
                    new ModelContent.Reasoning(message.get("reasoning_content").getAsString(), null);
            content.add(reasoning);
            events.accept(new ModelEvent.ReasoningDelta(reasoning.text()));
        }
        if (message.has("tool_calls")) {
            for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                JsonObject call = element.getAsJsonObject();
                JsonObject function = call.getAsJsonObject("function");
                ModelContent.ToolUse tool = new ModelContent.ToolUse(
                        requiredString(call, "id"),
                        requiredString(function, "name"),
                        JsonParser.parseString(requiredString(function, "arguments"))
                                .getAsJsonObject());
                content.add(tool);
                events.accept(new ModelEvent.ToolUseComplete(tool.id(), tool.name(), tool.input()));
            }
        }
        return content;
    }

    private static ModelUsage parseUsage(JsonObject object) {
        if (object == null) {
            return ModelUsage.empty();
        }
        long cached = 0;
        JsonObject details = object.getAsJsonObject("prompt_tokens_details");
        if (details != null && details.has("cached_tokens")) {
            cached = details.get("cached_tokens").getAsLong();
        }
        return new ModelUsage(
                value(object, "prompt_tokens"), value(object, "completion_tokens"), cached);
    }

    private static long value(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsLong()
                : 0;
    }

    private static String requiredString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing OpenAI response field: " + field);
        }
        return object.get(field).getAsString();
    }
}
