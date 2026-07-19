package dev.tomewisp.model.metadata;

import dev.tomewisp.guide.GuideFailure;
import java.util.Map;

/** Immutable credential-free cache event; it carries no resolved profile runtime. */
public record ModelMetadataUpdate(
        Map<ModelMetadata.Key, ModelMetadata> entries,
        GuideFailure failure) {
    public ModelMetadataUpdate {
        entries = Map.copyOf(entries);
    }
}
