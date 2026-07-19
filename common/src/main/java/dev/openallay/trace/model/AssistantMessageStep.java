package dev.openallay.trace.model;

public record AssistantMessageStep(String content) implements TraceStep {
    public AssistantMessageStep {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Assistant message content must not be blank");
        }
    }
}
