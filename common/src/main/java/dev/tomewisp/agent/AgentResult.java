package dev.tomewisp.agent;

import dev.tomewisp.agent.trace.LiveAgentTrace;

public record AgentResult(
        AgentState state,
        String text,
        String errorCode,
        String errorMessage,
        LiveAgentTrace trace) {
    public boolean successful() {
        return state == AgentState.COMPLETED;
    }
}
