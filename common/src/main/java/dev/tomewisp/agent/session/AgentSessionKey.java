package dev.tomewisp.agent.session;

import java.util.Objects;
import java.util.UUID;

public record AgentSessionKey(UUID actorId, String sessionId) {
    public AgentSessionKey {
        Objects.requireNonNull(actorId, "actorId");
        if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid Agent session ID: " + sessionId);
        }
    }

    public String schedulingKey() {
        return actorId + ":" + sessionId;
    }
}
