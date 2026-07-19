package dev.openallay.bridge.protocol;

import java.util.UUID;
import java.util.List;

public record ServerAgentRequestPayload(
        int version,
        UUID requestId,
        String sessionId,
        String question,
        boolean stream,
        List<ServerAgentHistoryMessage> history,
        List<String> clientToolIds) {
    public ServerAgentRequestPayload {
        BridgeProtocol.requireVersion(version);
        java.util.Objects.requireNonNull(requestId, "requestId");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid Agent session ID");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be blank");
        }
        history = List.copyOf(history);
        clientToolIds = List.copyOf(clientToolIds);
        java.util.HashSet<String> unique = new java.util.HashSet<>();
        for (String toolId : clientToolIds) {
            if (toolId == null
                    || !toolId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || !unique.add(toolId)) {
                throw new IllegalArgumentException("Invalid or duplicate client Tool ID");
            }
        }
    }

    public ServerAgentRequestPayload(
            int version, UUID requestId, String sessionId, String question, boolean stream) {
        this(version, requestId, sessionId, question, stream, List.of(), List.of());
    }

    public ServerAgentRequestPayload(
            int version,
            UUID requestId,
            String sessionId,
            String question,
            boolean stream,
            List<ServerAgentHistoryMessage> history) {
        this(version, requestId, sessionId, question, stream, history, List.of());
    }

    public ServerAgentRequestPayload withClientToolIds(List<String> replacement) {
        return new ServerAgentRequestPayload(
                version, requestId, sessionId, question, stream, history, replacement);
    }
}
