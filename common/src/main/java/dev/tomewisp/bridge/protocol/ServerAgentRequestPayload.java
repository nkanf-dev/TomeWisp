package dev.tomewisp.bridge.protocol;

import java.util.UUID;

public record ServerAgentRequestPayload(
        int version, UUID requestId, String sessionId, String question, boolean stream) {
    public ServerAgentRequestPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(requestId, "requestId");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid Agent session ID");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be blank");
        }
    }
}
