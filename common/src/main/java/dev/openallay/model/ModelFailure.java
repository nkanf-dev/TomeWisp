package dev.openallay.model;

public record ModelFailure(String code, String message, Integer httpStatus) implements ModelEvent {
    public ModelFailure {
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Model failure code and message are required");
        }
    }
}
