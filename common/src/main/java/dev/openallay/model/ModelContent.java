package dev.openallay.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.openallay.resource.projection.NormalizedResultModelProjector;
import dev.openallay.resource.projection.ResourceReceipt;
import java.util.List;
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

    /** Provider-facing Tool result; exact normalized truth stays outside model history. */
    record ToolResult(
            String toolUseId,
            String text,
            String receiptPath,
            List<ResourceReceipt> receipts,
            List<String> semanticUnits,
            boolean error)
            implements ModelContent {
        public ToolResult {
            if (toolUseId == null || toolUseId.isBlank()) {
                throw new IllegalArgumentException("Tool result requires toolUseId");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Tool result requires model text");
            }
            receiptPath = receiptPath == null || receiptPath.isBlank() ? null : receiptPath;
            receipts = List.copyOf(Objects.requireNonNull(receipts, "receipts"));
            semanticUnits = List.copyOf(Objects.requireNonNull(semanticUnits, "semanticUnits"));
            if (semanticUnits.stream().anyMatch(unit -> unit == null || unit.isBlank())) {
                throw new IllegalArgumentException("Tool result semantic units must not be blank");
            }
            if (!receipts.isEmpty() && receiptPath == null) {
                ResourceReceipt primary = receipts.getFirst();
                receiptPath = primary.resultPath() == null ? null : primary.resultPath().toString();
            }
        }

        public ToolResult(String toolUseId, String text, String receiptPath, boolean error) {
            this(toolUseId, text, receiptPath, List.of(), List.of(text), error);
        }

        public ToolResult(
                String toolUseId,
                String text,
                String receiptPath,
                List<ResourceReceipt> receipts,
                boolean error) {
            this(toolUseId, text, receiptPath, receipts, List.of(text), error);
        }

        public ToolResult(String toolUseId, String text, boolean error) {
            this(toolUseId, text, null, List.of(), List.of(text), error);
        }

        /** Transitional constructor for restored pre-VFS history and old bridge fixtures. */
        public ToolResult(String toolUseId, JsonElement value, boolean error) {
            this(toolUseId, legacyText(value), null, List.of(),
                    List.of(legacyText(value)), error);
        }

        /** Transitional view for older structure/reducer callers; always model text. */
        public JsonElement value() {
            return new JsonPrimitive(text);
        }

        private static String legacyText(JsonElement value) {
            Objects.requireNonNull(value, "value");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString();
            }
            if (value.isJsonObject()) {
                return NormalizedResultModelProjector.project(value.getAsJsonObject()).text();
            }
            return "status: retained\nmessage: Legacy structured Tool result is available only in exact history";
        }
    }
}
