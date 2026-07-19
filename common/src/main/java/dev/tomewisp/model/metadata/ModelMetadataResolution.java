package dev.tomewisp.model.metadata;

import dev.tomewisp.guide.GuideFailure;

/** Effective limits after explicit configuration is applied over one discovery result. */
public record ModelMetadataResolution(
        Integer contextWindowTokens,
        Integer maxOutputTokens,
        ModelMetadata metadata,
        GuideFailure failure) {
    public ModelMetadataResolution {
        if ((metadata == null) == (failure == null)) {
            throw new IllegalArgumentException(
                    "metadata resolution must contain exactly one metadata value or failure");
        }
        if (contextWindowTokens != null && contextWindowTokens <= 0) {
            throw new IllegalArgumentException("context window must be positive");
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("output limit must be positive");
        }
        if (failure != null && (contextWindowTokens != null || maxOutputTokens != null)) {
            throw new IllegalArgumentException("failed metadata resolution cannot supply limits");
        }
    }

    public static ModelMetadataResolution resolved(
            ModelMetadata metadata,
            Integer explicitContextWindowTokens,
            Integer explicitMaxOutputTokens) {
        java.util.Objects.requireNonNull(metadata, "metadata");
        return new ModelMetadataResolution(
                explicitContextWindowTokens == null
                        ? metadata.contextWindowTokens()
                        : explicitContextWindowTokens,
                explicitMaxOutputTokens == null
                        ? metadata.maxOutputTokens()
                        : explicitMaxOutputTokens,
                metadata,
                null);
    }

    public static ModelMetadataResolution failed(String code, String message) {
        return new ModelMetadataResolution(
                null, null, null, new GuideFailure(code, message));
    }

    public boolean successful() {
        return metadata != null;
    }
}
