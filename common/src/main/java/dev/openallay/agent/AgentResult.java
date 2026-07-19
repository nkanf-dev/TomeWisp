package dev.openallay.agent;

import dev.openallay.agent.trace.LiveAgentTrace;

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
