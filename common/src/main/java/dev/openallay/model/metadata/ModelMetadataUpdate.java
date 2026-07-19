package dev.openallay.model.metadata;

import dev.openallay.guide.GuideFailure;
import java.util.Map;

/** Immutable credential-free cache event; it carries no resolved profile runtime. */
public record ModelMetadataUpdate(
        Map<ModelMetadata.Key, ModelMetadata> entries,
        GuideFailure failure) {
    public ModelMetadataUpdate {
        entries = Map.copyOf(entries);
    }
}
