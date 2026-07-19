package dev.openallay.agent.context;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ContextCheckpoint(
        UUID checkpointId,
        int sourceFromIndex,
        int sourceToIndexExclusive,
        String sourceHash,
        String modelIdentifier,
        int promptVersion,
        int schemaVersion,
        Instant createdAt,
        Status status,
        String summary,
        String failureCode,
        String failureMessage,
        int estimatedProjectionTokens) {
    public enum Status {
        SUCCEEDED,
        FAILED
    }

    public ContextCheckpoint {
        Objects.requireNonNull(checkpointId, "checkpointId");
        if (sourceFromIndex < 0 || sourceToIndexExclusive <= sourceFromIndex) {
            throw new IllegalArgumentException("checkpoint source range is invalid");
        }
        if (sourceHash == null || !sourceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checkpoint sourceHash must be lowercase SHA-256");
        }
        if (modelIdentifier == null || modelIdentifier.isBlank()) {
            throw new IllegalArgumentException("checkpoint modelIdentifier is required");
        }
        if (promptVersion <= 0 || schemaVersion <= 0) {
            throw new IllegalArgumentException("checkpoint versions must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(status, "status");
        if (estimatedProjectionTokens < 0) {
            throw new IllegalArgumentException("checkpoint estimate must be non-negative");
        }
        if (status == Status.SUCCEEDED) {
            if (summary == null || summary.isBlank() || failureCode != null || failureMessage != null) {
                throw new IllegalArgumentException("successful checkpoint requires only a summary");
            }
        } else if (summary != null
                || failureCode == null || failureCode.isBlank()
                || failureMessage == null || failureMessage.isBlank()) {
            throw new IllegalArgumentException("failed checkpoint requires only failure details");
        }
    }
}
