package dev.openallay.bridge.protocol;

import java.util.UUID;

/** One server-hosted Agent invocation bound to the requesting player's client. */
public record ClientToolCallPayload(
        int version,
        UUID requestId,
        UUID invocationId,
        String sessionId,
        String toolId,
        String viewId,
        String argumentsJson) {
    public ClientToolCallPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(invocationId, "invocationId");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid Agent session ID");
        }
        if (toolId == null || !toolId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid client Tool ID");
        }
        viewId = BridgeViewIdentity.require(viewId);
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new IllegalArgumentException("Client Tool arguments are required");
        }
    }

    public ClientToolCallPayload(
            int version,
            UUID requestId,
            UUID invocationId,
            String sessionId,
            String toolId,
            String argumentsJson) {
        this(
                version,
                requestId,
                invocationId,
                sessionId,
                toolId,
                BridgeViewIdentity.forRequest(
                        requestId, sessionId, BridgeViewIdentity.Owner.CLIENT),
                argumentsJson);
    }
}
