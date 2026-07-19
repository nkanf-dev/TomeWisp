package dev.tomewisp.guide;

import dev.tomewisp.agent.context.ContextBudget;

/** Selected endpoint's actual model budget and canonical reuse identity. */
public record GuideContextSpec(
        ContextBudget budget,
        int promptAndToolTokens,
        String canonicalModelId) {
    public GuideContextSpec {
        java.util.Objects.requireNonNull(budget, "budget");
        if (promptAndToolTokens < 0 || promptAndToolTokens >= budget.inputTokens()) {
            throw new IllegalArgumentException("prompt/tool reservation exhausts model input");
        }
        if (canonicalModelId == null || canonicalModelId.isBlank()) {
            throw new IllegalArgumentException("canonical model ID is required");
        }
    }
}
