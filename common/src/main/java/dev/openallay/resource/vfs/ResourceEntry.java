package dev.openallay.resource.vfs;

import java.util.Objects;

public record ResourceEntry(ResourcePath path, ResourceKind kind, String label) implements Comparable<ResourceEntry> {
    public ResourceEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
    }

    @Override
    public int compareTo(ResourceEntry other) {
        return path.compareTo(other.path);
    }
}
