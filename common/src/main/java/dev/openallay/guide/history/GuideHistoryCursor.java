package dev.openallay.guide.history;

import java.util.UUID;

/** Stable exclusive request cursor inside one durable session. */
public record GuideHistoryCursor(long sequence, UUID requestId) {
    public GuideHistoryCursor {
        if (sequence < 0) {
            throw new IllegalArgumentException("history sequence must not be negative");
        }
        java.util.Objects.requireNonNull(requestId, "requestId");
    }
}
