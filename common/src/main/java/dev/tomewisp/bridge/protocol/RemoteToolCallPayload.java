package dev.tomewisp.bridge.protocol;

import java.util.UUID;

public record RemoteToolCallPayload(
        int version, UUID correlationId, String sessionId, String toolId, String argumentsJson) {
    public RemoteToolCallPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(correlationId, "correlationId");
        require(sessionId, "sessionId");
        require(toolId, "toolId");
        require(argumentsJson, "argumentsJson");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
