package dev.openallay.guide;

public record GuideFailure(String code, String message) {
    public GuideFailure {
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("failure code and message are required");
        }
    }
}
