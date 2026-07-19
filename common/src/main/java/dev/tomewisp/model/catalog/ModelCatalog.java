package dev.tomewisp.model.catalog;

import java.util.List;

/** Ephemeral validated provider model IDs; no credential or raw response is retained. */
public record ModelCatalog(List<String> modelIds) {
    public ModelCatalog {
        modelIds = List.copyOf(modelIds);
        if (modelIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("model catalog IDs must be nonblank");
        }
        if (modelIds.size() != new java.util.LinkedHashSet<>(modelIds).size()) {
            throw new IllegalArgumentException("model catalog IDs must be duplicate-free");
        }
    }
}
