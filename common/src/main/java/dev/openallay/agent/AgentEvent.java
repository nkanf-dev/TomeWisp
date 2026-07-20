package dev.openallay.agent;

import com.google.gson.JsonObject;
import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.agent.tool.ToolResultDiagnostics;
import dev.openallay.agent.tool.ToolUiReference;
import dev.openallay.guide.GuideToolMessage;
import dev.openallay.model.ModelEvent;
import java.util.List;
import java.util.Objects;

public sealed interface AgentEvent
        permits AgentEvent.StateChanged,
                AgentEvent.ContextCompacted,
                AgentEvent.ModelProgress,
                AgentEvent.ToolStarted,
                AgentEvent.ToolCompleted,
                AgentEvent.FinalText,
                AgentEvent.Failed {
    record StateChanged(AgentState state) implements AgentEvent {}

    record ContextCompacted(ContextCheckpoint checkpoint) implements AgentEvent {
        public ContextCompacted {
            Objects.requireNonNull(checkpoint, "checkpoint");
        }
    }

    record ModelProgress(ModelEvent event) implements AgentEvent {
        public ModelProgress {
            Objects.requireNonNull(event, "event");
        }
    }

    record ToolStarted(
            String invocationId,
            String toolId,
            List<GuideToolMessage> presentationMessages)
            implements AgentEvent {
        public ToolStarted {
            requireIdentity(invocationId, toolId);
            presentationMessages = List.copyOf(presentationMessages);
        }

        public ToolStarted(String invocationId, String toolId) {
            this(invocationId, toolId, List.of());
        }
    }

    record ToolCompleted(
            String invocationId,
            String toolId,
            boolean failure,
            JsonObject normalized,
            ToolUiReference uiReference,
            ToolResultDiagnostics diagnostics)
            implements AgentEvent {
        public ToolCompleted {
            requireIdentity(invocationId, toolId);
            normalized = Objects.requireNonNull(normalized, "normalized").deepCopy();
            uiReference = Objects.requireNonNull(uiReference, "uiReference");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        }

        public ToolCompleted(
                String invocationId,
                String toolId,
                boolean failure,
                JsonObject normalized) {
            this(
                    invocationId,
                    toolId,
                    failure,
                    normalized,
                    ToolUiReference.none(),
                    ToolResultDiagnostics.none());
        }

        @Override
        public JsonObject normalized() {
            return normalized.deepCopy();
        }
    }

    record FinalText(String text) implements AgentEvent {}

    record Failed(String code, String message) implements AgentEvent {}

    private static void requireIdentity(String invocationId, String toolId) {
        if (invocationId == null || invocationId.isBlank()
                || toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("tool invocation identity is required");
        }
    }
}
