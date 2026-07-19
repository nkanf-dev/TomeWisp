package dev.openallay.model.anthropic;

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

final class AnthropicStreamAccumulator {
    private final Consumer<ModelEvent> events;
    private final Map<Integer, Block> blocks = new TreeMap<>();
    private String model;
    private String stopReason;
    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;

    AnthropicStreamAccumulator(Consumer<ModelEvent> events) {
        this.events = events;
    }

    void accept(SseEvent event) {
        if (event.data().equals("[DONE]")) {
            return;
        }
        JsonObject root = JsonParser.parseString(event.data()).getAsJsonObject();
        String type = root.has("type") ? root.get("type").getAsString() : event.event();
        switch (type) {
            case "message_start" -> messageStart(root.getAsJsonObject("message"));
            case "content_block_start" -> blockStart(
                    root.get("index").getAsInt(), root.getAsJsonObject("content_block"));
            case "content_block_delta" -> blockDelta(
                    root.get("index").getAsInt(), root.getAsJsonObject("delta"));
            case "content_block_stop" -> blockStop(root.get("index").getAsInt());
            case "message_delta" -> messageDelta(root);
            case "message_stop", "ping" -> {}
            case "error" -> throw new IllegalArgumentException("Anthropic SSE error event");
            default -> throw new IllegalArgumentException("Unknown Anthropic SSE event: " + type);
        }
    }

    ModelTurn finish() {
        if (model == null || stopReason == null) {
            throw new IllegalArgumentException("Incomplete Anthropic SSE message");
        }
        List<ModelContent> content = new ArrayList<>();
        for (Block block : blocks.values()) {
            content.add(block.toContent());
        }
        ModelUsage usage = new ModelUsage(inputTokens, outputTokens, cacheReadTokens);
        events.accept(new ModelEvent.UsageUpdate(usage));
        events.accept(new ModelEvent.MessageComplete(stopReason));
        return new ModelTurn("anthropic_messages", model, content, stopReason, usage);
    }

    private void messageStart(JsonObject message) {
        model = message.get("model").getAsString();
        JsonObject usage = message.getAsJsonObject("usage");
        if (usage != null) {
            inputTokens = value(usage, "input_tokens");
            cacheReadTokens = value(usage, "cache_read_input_tokens");
        }
    }

    private void blockStart(int index, JsonObject content) {
        String type = content.get("type").getAsString();
        Block block = new Block(type);
        if (type.equals("text") && content.has("text")) {
            block.value.append(content.get("text").getAsString());
        } else if (type.equals("thinking") && content.has("thinking")) {
            block.value.append(content.get("thinking").getAsString());
            block.signature = content.has("signature") ? content.get("signature").getAsString() : null;
        } else if (type.equals("tool_use")) {
            block.id = content.get("id").getAsString();
            block.name = content.get("name").getAsString();
            if (content.has("input") && !content.getAsJsonObject("input").isEmpty()) {
                block.value.append(content.get("input").toString());
            }
        }
        blocks.put(index, block);
    }

    private void blockDelta(int index, JsonObject delta) {
        Block block = requiredBlock(index);
        String type = delta.get("type").getAsString();
        switch (type) {
            case "text_delta" -> {
                String text = delta.get("text").getAsString();
                block.value.append(text);
                events.accept(new ModelEvent.TextDelta(text));
            }
            case "thinking_delta" -> {
                String text = delta.get("thinking").getAsString();
                block.value.append(text);
                events.accept(new ModelEvent.ReasoningDelta(text));
            }
            case "signature_delta" -> block.signature = delta.get("signature").getAsString();
            case "input_json_delta" -> block.value.append(delta.get("partial_json").getAsString());
            default -> throw new IllegalArgumentException("Unknown Anthropic delta: " + type);
        }
    }

    private void blockStop(int index) {
        Block block = requiredBlock(index);
        if (block.type.equals("tool_use")) {
            ModelContent.ToolUse tool = (ModelContent.ToolUse) block.toContent();
            events.accept(new ModelEvent.ToolUseComplete(tool.id(), tool.name(), tool.input()));
        }
    }

    private void messageDelta(JsonObject root) {
        JsonObject delta = root.getAsJsonObject("delta");
        if (delta != null && delta.has("stop_reason") && !delta.get("stop_reason").isJsonNull()) {
            stopReason = delta.get("stop_reason").getAsString();
        }
        JsonObject usage = root.getAsJsonObject("usage");
        if (usage != null) {
            outputTokens = value(usage, "output_tokens");
        }
    }

    private Block requiredBlock(int index) {
        Block block = blocks.get(index);
        if (block == null) {
            throw new IllegalArgumentException("Anthropic delta references unknown block " + index);
        }
        return block;
    }

    private static long value(JsonObject object, String field) {
        return object.has(field) ? object.get(field).getAsLong() : 0;
    }

    private static final class Block {
        private final String type;
        private final StringBuilder value = new StringBuilder();
        private String id;
        private String name;
        private String signature;

        private Block(String type) {
            this.type = type;
        }

        private ModelContent toContent() {
            return switch (type) {
                case "text" -> new ModelContent.Text(value.toString());
                case "thinking" -> new ModelContent.Reasoning(value.toString(), signature);
                case "tool_use" -> new ModelContent.ToolUse(
                        id,
                        name,
                        value.isEmpty()
                                ? new JsonObject()
                                : JsonParser.parseString(value.toString()).getAsJsonObject());
                default -> throw new IllegalArgumentException("Unsupported Anthropic block: " + type);
            };
        }
    }
}
