package dev.tomewisp.agent;

import com.google.gson.JsonObject;
import dev.tomewisp.model.ModelEvent;
import java.util.Objects;

public sealed interface AgentEvent
        permits AgentEvent.StateChanged,
                AgentEvent.ModelProgress,
                AgentEvent.ToolStarted,
                AgentEvent.ToolCompleted,
                AgentEvent.FinalText,
                AgentEvent.Failed {
    record StateChanged(AgentState state) implements AgentEvent {}

    record ModelProgress(ModelEvent event) implements AgentEvent {}

    record ToolStarted(String toolId) implements AgentEvent {}

    record ToolCompleted(String toolId, boolean failure, JsonObject normalized)
            implements AgentEvent {
        public ToolCompleted {
            if (toolId == null || toolId.isBlank()) {
                throw new IllegalArgumentException("toolId must not be blank");
            }
            normalized = Objects.requireNonNull(normalized, "normalized").deepCopy();
        }

        @Override
        public JsonObject normalized() {
            return normalized.deepCopy();
        }
    }

    record FinalText(String text) implements AgentEvent {}

    record Failed(String code, String message) implements AgentEvent {}
}
