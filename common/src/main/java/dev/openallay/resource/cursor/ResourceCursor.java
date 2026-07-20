package dev.openallay.resource.cursor;

import dev.openallay.resource.vfs.ResourcePath;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Internal semantic continuation state. The model only receives the opaque store token. */
public record ResourceCursor(
        UUID actorId,
        String sessionId,
        String requestId,
        long connectionGeneration,
        Map<String, String> viewGenerations,
        String queryDigest,
        ResourcePath path,
        PositionKind positionKind,
        long nextPosition,
        Instant expiresAt) {
    public enum PositionKind {
        CHILD_ENTRY,
        RECORD,
        TABLE_ROW,
        DOCUMENT_SECTION,
        TEXT_LINE
    }

    public ResourceCursor {
        Objects.requireNonNull(actorId, "actorId");
        sessionId = requireText(sessionId, "sessionId");
        requestId = requireText(requestId, "requestId");
        if (connectionGeneration < 0) {
            throw new IllegalArgumentException("connectionGeneration must be non-negative");
        }
        viewGenerations = Map.copyOf(Objects.requireNonNull(viewGenerations, "viewGenerations"));
        if (viewGenerations.isEmpty()) {
            throw new IllegalArgumentException("viewGenerations must not be empty");
        }
        queryDigest = requireText(queryDigest, "queryDigest");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(positionKind, "positionKind");
        if (nextPosition < 0) {
            throw new IllegalArgumentException("nextPosition must be non-negative");
        }
        // null means the cursor expires only with its request/session/connection lifecycle.
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
