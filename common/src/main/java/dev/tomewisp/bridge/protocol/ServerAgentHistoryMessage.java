package dev.tomewisp.bridge.protocol;

public record ServerAgentHistoryMessage(Role role, String text) {
    public enum Role {
        USER,
        ASSISTANT
    }

    public ServerAgentHistoryMessage {
        java.util.Objects.requireNonNull(role, "role");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Server Agent history text must not be blank");
        }
    }
}
