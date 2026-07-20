package dev.openallay.resource.vfs;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public record ResourceViewScope(
        UUID actorId,
        String sessionId,
        String requestId,
        long connectionGeneration,
        String topology,
        Set<String> capabilities,
        Instant createdAt,
        BooleanSupplier cancelled) {
    public ResourceViewScope {
        Objects.requireNonNull(actorId, "actorId");
        sessionId = requireText(sessionId, "sessionId");
        requestId = requireText(requestId, "requestId");
        if (connectionGeneration < 0) {
            throw new IllegalArgumentException("connectionGeneration must be non-negative");
        }
        topology = requireText(topology, "topology");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(cancelled, "cancelled");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
