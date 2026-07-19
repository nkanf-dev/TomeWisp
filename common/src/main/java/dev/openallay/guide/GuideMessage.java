package dev.openallay.guide;

import java.time.Instant;
import java.util.UUID;

public record GuideMessage(UUID requestId, Role role, String text, Instant createdAt) {
    public enum Role { USER, ASSISTANT }

    public GuideMessage {
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(role, "role");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("message text must not be blank");
        }
        java.util.Objects.requireNonNull(createdAt, "createdAt");
    }
}
