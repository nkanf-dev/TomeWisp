package dev.tomewisp.model;

import java.time.Duration;

public final class ModelRateLimitException extends ModelClientException {
    private final Duration retryAfter;

    public ModelRateLimitException(String message, Duration retryAfter) {
        super(new ModelFailure("model_rate_limited", message, 429));
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
