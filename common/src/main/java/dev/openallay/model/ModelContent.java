package dev.openallay.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;

public sealed interface ModelContent
        permits ModelContent.Text,
                ModelContent.Reasoning,
                ModelContent.ToolUse,
                ModelContent.ToolResult {
    record Text(String text) implements ModelContent {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    record Reasoning(String text, String signature) implements ModelContent {
        public Reasoning {
            Objects.requireNonNull(text, "text");
        }
    }

    record ToolUse(String id, String name, JsonObject input) implements ModelContent {
        public ToolUse {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Tool-use id and name are required");
            }
            input = Objects.requireNonNull(input, "input").deepCopy();
        }

        @Override
        public JsonObject input() {
            return input.deepCopy();
        }
    }

    record ToolResult(String toolUseId, JsonElement value, boolean error) implements ModelContent {
        public ToolResult {
            if (toolUseId == null || toolUseId.isBlank()) {
                throw new IllegalArgumentException("Tool result requires toolUseId");
            }
            value = Objects.requireNonNull(value, "value").deepCopy();
        }

        @Override
        public JsonElement value() {
            return value.deepCopy();
        }
    }
}
