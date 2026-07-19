package dev.openallay.model.metadata;

import java.time.Instant;
import java.util.Objects;

/** Credential-free capability metadata published by one trusted provider. */
public record ModelMetadata(
        String source,
        String providerModelId,
        String canonicalModelId,
        int contextWindowTokens,
        Integer maxOutputTokens,
        Instant capturedAt) {
    public ModelMetadata {
        if (source == null || source.isBlank()
                || providerModelId == null || providerModelId.isBlank()
                || canonicalModelId == null || canonicalModelId.isBlank()) {
            throw new IllegalArgumentException("model metadata identity must not be blank");
        }
        if (contextWindowTokens <= 0
                || (maxOutputTokens != null && maxOutputTokens <= 0)) {
            throw new IllegalArgumentException("model metadata limits must be positive");
        }
        Objects.requireNonNull(capturedAt, "capturedAt");
    }

    public Key key() {
        return new Key(source, providerModelId);
    }

    public record Key(String source, String providerModelId) {
        public Key {
            if (source == null || source.isBlank()
                    || providerModelId == null || providerModelId.isBlank()) {
                throw new IllegalArgumentException("metadata cache key must not be blank");
            }
        }
    }
}
