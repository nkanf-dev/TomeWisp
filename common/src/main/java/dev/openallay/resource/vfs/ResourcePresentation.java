package dev.openallay.resource.vfs;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record ResourcePresentation(Kind kind, Map<String, String> references) {
    public ResourcePresentation {
        Objects.requireNonNull(kind, "kind");
        TreeMap<String, String> copy = new TreeMap<>();
        Objects.requireNonNull(references, "references").forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("Presentation references must be non-blank");
            }
            copy.put(key, value);
        });
        references = Map.copyOf(copy);
    }

    public static ResourcePresentation none() {
        return new ResourcePresentation(Kind.NONE, Map.of());
    }

    public enum Kind {
        NONE, ITEM, RECIPE, DOCUMENT, TABLE, OPTIONS, DIAGNOSTICS, BINARY
    }
}
