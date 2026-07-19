package dev.openallay.trace.model;

import dev.openallay.context.ContextCapability;
import java.util.List;
import java.util.Set;

public record AgentTrace(
        int schemaVersion,
        String id,
        String userMessage,
        Set<ContextCapability> requiredContext,
        List<TraceStep> steps) {
    public AgentTrace {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported trace schema: " + schemaVersion);
        }
        if (id == null || !id.matches("[a-z0-9][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid trace id: " + id);
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        requiredContext = Set.copyOf(requiredContext);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Trace must contain at least one step");
        }
    }
}
