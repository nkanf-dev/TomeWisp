package dev.tomewisp.bridge.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.model.ModelContent;

/** Strict provider-neutral history content carried to a server-hosted model. */
public record ServerAgentHistoryContent(
        Kind kind,
        String text,
        String toolUseId,
        String toolName,
        String json,
        Boolean error) {
    public enum Kind {
        TEXT,
        TOOL_USE,
        TOOL_RESULT
    }

    public ServerAgentHistoryContent {
        java.util.Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case TEXT -> {
                if (text == null || toolUseId != null || toolName != null
                        || json != null || error != null) {
                    throw new IllegalArgumentException("Malformed text history content");
                }
            }
            case TOOL_USE -> {
                if (text != null || blank(toolUseId) || blank(toolName)
                        || blank(json) || error != null
                        || !JsonParser.parseString(json).isJsonObject()) {
                    throw new IllegalArgumentException("Malformed tool-use history content");
                }
            }
            case TOOL_RESULT -> {
                if (text != null || blank(toolUseId) || toolName != null
                        || blank(json) || error == null) {
                    throw new IllegalArgumentException("Malformed tool-result history content");
                }
                JsonParser.parseString(json);
            }
        }
    }

    public static ServerAgentHistoryContent from(ModelContent content) {
        return switch (content) {
            case ModelContent.Text value -> new ServerAgentHistoryContent(
                    Kind.TEXT, value.text(), null, null, null, null);
            case ModelContent.ToolUse value -> new ServerAgentHistoryContent(
                    Kind.TOOL_USE, null, value.id(), value.name(), value.input().toString(), null);
            case ModelContent.ToolResult value -> new ServerAgentHistoryContent(
                    Kind.TOOL_RESULT, null, value.toolUseId(), null,
                    value.value().toString(), value.error());
            case ModelContent.Reasoning ignored -> throw new IllegalArgumentException(
                    "Reasoning content cannot enter durable bridge history");
        };
    }

    public ModelContent toModelContent() {
        return switch (kind) {
            case TEXT -> new ModelContent.Text(text);
            case TOOL_USE -> new ModelContent.ToolUse(
                    toolUseId, toolName, JsonParser.parseString(json).getAsJsonObject());
            case TOOL_RESULT -> new ModelContent.ToolResult(
                    toolUseId, JsonParser.parseString(json), error);
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
