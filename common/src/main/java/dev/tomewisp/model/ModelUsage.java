package dev.tomewisp.model;

public record ModelUsage(long inputTokens, long outputTokens, long cacheReadTokens) {
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0) {
            throw new IllegalArgumentException("Model usage values must be non-negative");
        }
    }

    public static ModelUsage empty() {
        return new ModelUsage(0, 0, 0);
    }
}
