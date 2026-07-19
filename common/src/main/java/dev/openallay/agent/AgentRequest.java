package dev.openallay.agent;

import dev.openallay.context.ToolInvocationContext;
import java.util.Objects;
import java.util.UUID;

public record AgentRequest(
        UUID requestId,
        UUID actorId,
        String sessionId,
        String userMessage,
        String systemPrompt,
        ToolInvocationContext context,
        boolean stream) {
    public AgentRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(actorId, "actorId");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid Agent session ID: " + sessionId);
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("Agent user message must not be blank");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("Agent system prompt must not be blank");
        }
        Objects.requireNonNull(context, "context");
    }

    public dev.openallay.agent.session.AgentSessionKey sessionKey() {
        return new dev.openallay.agent.session.AgentSessionKey(actorId, sessionId);
    }
}
