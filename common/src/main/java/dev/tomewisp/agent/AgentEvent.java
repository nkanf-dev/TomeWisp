package dev.tomewisp.agent;

import dev.tomewisp.model.ModelEvent;

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

    record ToolCompleted(String toolId, boolean failure) implements AgentEvent {}

    record FinalText(String text) implements AgentEvent {}

    record Failed(String code, String message) implements AgentEvent {}
}
