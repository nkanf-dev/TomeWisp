package dev.openallay.knowledge.online;

public final class OnlineKnowledgeException extends RuntimeException {
    private final String code;

    public OnlineKnowledgeException(String code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
