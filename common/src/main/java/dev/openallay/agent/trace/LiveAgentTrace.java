package dev.openallay.agent.trace;

import dev.openallay.agent.AgentState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LiveAgentTrace(
        int schemaVersion,
        UUID requestId,
        UUID actorId,
        String sessionId,
        Instant startedAt,
        Instant completedAt,
        AgentState finalState,
        List<LiveTraceEvent> events,
        String finalText,
        String errorCode) {
    public LiveAgentTrace {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported live trace schema");
        }
        events = List.copyOf(events);
    }
}
