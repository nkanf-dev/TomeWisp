package dev.openallay.bridge.protocol;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Request-scoped identity for one immutable resource view across a bridge hop. */
public final class BridgeViewIdentity {
    public enum Owner { CLIENT, SERVER }

    private BridgeViewIdentity() {}

    public static String forRequest(UUID requestId, String sessionId, Owner owner) {
        java.util.Objects.requireNonNull(requestId, "requestId");
        return forRequest(requestId.toString(), sessionId, owner);
    }

    public static String forRequest(String requestId, String sessionId, Owner owner) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        java.util.Objects.requireNonNull(owner, "owner");
        return digest(requestId + "\n" + requireSession(sessionId) + "\n" + owner.name());
    }

    public static String forOperation(UUID operationId) {
        java.util.Objects.requireNonNull(operationId, "operationId");
        return digest("operation\n" + operationId);
    }

    public static String require(String viewId) {
        if (viewId == null || !viewId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid bridge resource view identity");
        }
        return viewId;
    }

    private static String digest(String value) {
        return ResultChunker.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return sessionId;
    }
}
