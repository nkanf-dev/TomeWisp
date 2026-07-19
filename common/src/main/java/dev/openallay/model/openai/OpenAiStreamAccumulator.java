package dev.openallay.model.openai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelTurn;
import dev.openallay.model.ModelUsage;
import dev.openallay.model.http.SseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

final class OpenAiStreamAccumulator {
    private final Consumer<ModelEvent> events;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, Tool> tools = new TreeMap<>();
    private String model;
    private String stopReason;
    private ModelUsage usage = ModelUsage.empty();

    OpenAiStreamAccumulator(Consumer<ModelEvent> events) {
        this.events = events;
    }

    void accept(SseEvent event) {
        if (event.data().equals("[DONE]")) {
            return;
        }
        JsonObject root = JsonParser.parseString(event.data()).getAsJsonObject();
        if (root.has("model")) {
            model = root.get("model").getAsString();
        }
        if (root.has("usage") && !root.get("usage").isJsonNull()) {
            JsonObject value = root.getAsJsonObject("usage");
            usage = new ModelUsage(
                    number(value, "prompt_tokens"),
                    number(value, "completion_tokens"),
                    value.has("prompt_tokens_details")
                            ? number(value.getAsJsonObject("prompt_tokens_details"), "cached_tokens")
                            : 0);
        }
        if (!root.has("choices") || root.getAsJsonArray("choices").isEmpty()) {
            return;
        }
        JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
            stopReason = choice.get("finish_reason").getAsString();
        }
        JsonObject delta = choice.getAsJsonObject("delta");
        if (delta == null) {
            return;
        }
        append(delta, "content", text, value -> events.accept(new ModelEvent.TextDelta(value)));
        append(
                delta,
                "reasoning_content",
                reasoning,
                value -> events.accept(new ModelEvent.ReasoningDelta(value)));
        if (delta.has("tool_calls")) {
            for (JsonElement element : delta.getAsJsonArray("tool_calls")) {
                JsonObject call = element.getAsJsonObject();
                int index = call.get("index").getAsInt();
                Tool tool = tools.computeIfAbsent(index, ignored -> new Tool());
                if (call.has("id")) {
                    tool.id = call.get("id").getAsString();
                }
                JsonObject function = call.getAsJsonObject("function");
                if (function != null) {
                    if (function.has("name")) {
                        tool.name.append(function.get("name").getAsString());
                    }
                    if (function.has("arguments")) {
                        tool.arguments.append(function.get("arguments").getAsString());
                    }
                }
            }
        }
    }

    ModelTurn finish() {
        if (model == null || stopReason == null) {
            throw new IllegalArgumentException("Incomplete OpenAI SSE message");
        }
        List<ModelContent> content = new ArrayList<>();
        if (!text.isEmpty()) {
            content.add(new ModelContent.Text(text.toString()));
        }
        if (!reasoning.isEmpty()) {
            content.add(new ModelContent.Reasoning(reasoning.toString(), null));
        }
        for (Tool value : tools.values()) {
            ModelContent.ToolUse tool = new ModelContent.ToolUse(
                    value.id,
                    value.name.toString(),
                    JsonParser.parseString(value.arguments.toString()).getAsJsonObject());
            content.add(tool);
            events.accept(new ModelEvent.ToolUseComplete(tool.id(), tool.name(), tool.input()));
        }
        events.accept(new ModelEvent.UsageUpdate(usage));
        events.accept(new ModelEvent.MessageComplete(stopReason));
        return new ModelTurn("openai_chat", model, content, stopReason, usage);
    }

    private static void append(
            JsonObject object,
            String field,
            StringBuilder target,
            Consumer<String> event) {
        if (object.has(field) && !object.get(field).isJsonNull()) {
            String value = object.get(field).getAsString();
            target.append(value);
            event.accept(value);
        }
    }

    private static long number(JsonObject object, String field) {
        return object != null && object.has(field) ? object.get(field).getAsLong() : 0;
    }

    private static final class Tool {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
