package dev.openallay.agent.tool;

import java.time.Instant;

public record ToolResultDiagnostics(
        int normalizedBytes,
        int modelCharacters,
        String generationId,
        Instant projectedAt) {
    public ToolResultDiagnostics {
        if (normalizedBytes < 0 || modelCharacters < 0) {
            throw new IllegalArgumentException("Tool result sizes must be non-negative");
        }
        generationId = generationId == null ? "legacy" : generationId;
        projectedAt = projectedAt == null ? Instant.EPOCH : projectedAt;
    }

    public static ToolResultDiagnostics none() {
        return new ToolResultDiagnostics(0, 0, "unavailable", Instant.EPOCH);
    }
}
