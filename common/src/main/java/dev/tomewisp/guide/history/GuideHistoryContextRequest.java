package dev.tomewisp.guide.history;

import dev.tomewisp.agent.context.ContextBudget;

/** Actual selected-model budget plus non-history input reservations. */
public record GuideHistoryContextRequest(
        GuideHistoryScope scope,
        String sessionId,
        ContextBudget budget,
        int promptAndToolTokens,
        String modelIdentifier) {
    public GuideHistoryContextRequest {
        java.util.Objects.requireNonNull(scope, "scope");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid session ID");
        }
        java.util.Objects.requireNonNull(budget, "budget");
        if (promptAndToolTokens < 0 || promptAndToolTokens >= budget.inputTokens()) {
            throw new IllegalArgumentException("prompt/tool reservation exhausts model input");
        }
        if (modelIdentifier == null || modelIdentifier.isBlank()) {
            throw new IllegalArgumentException("model identifier is required");
        }
    }

    public int availableHistoryTokens() {
        return budget.inputTokens() - promptAndToolTokens;
    }
}
