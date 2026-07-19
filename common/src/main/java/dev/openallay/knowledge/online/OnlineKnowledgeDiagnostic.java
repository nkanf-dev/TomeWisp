package dev.openallay.knowledge.online;

public record OnlineKnowledgeDiagnostic(String sourceId, String code, String message) {
    public OnlineKnowledgeDiagnostic {
        if (sourceId == null || sourceId.isBlank()
                || code == null || code.isBlank()
                || message == null || message.isBlank()) {
            throw new IllegalArgumentException("invalid online knowledge diagnostic");
        }
    }
}
