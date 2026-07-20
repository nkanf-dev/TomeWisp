package dev.openallay.bridge.protocol;

import java.util.UUID;

public record RemoteToolCallPayload(
        int version,
        UUID correlationId,
        String sessionId,
        String toolId,
        String viewId,
        String argumentsJson) {
    public RemoteToolCallPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(correlationId, "correlationId");
        require(sessionId, "sessionId");
        require(toolId, "toolId");
        viewId = BridgeViewIdentity.require(viewId);
        require(argumentsJson, "argumentsJson");
    }

    public RemoteToolCallPayload(
            int version, UUID correlationId, String sessionId, String toolId, String argumentsJson) {
        this(
                version,
                correlationId,
                sessionId,
                toolId,
                BridgeViewIdentity.forRequest(
                        correlationId, sessionId, BridgeViewIdentity.Owner.SERVER),
                argumentsJson);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
