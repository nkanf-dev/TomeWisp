package dev.openallay.resource.vfs;

import java.util.Objects;

public record ResourceLink(String relation, ResourcePath target, String label) implements Comparable<ResourceLink> {
    public ResourceLink {
        if (relation == null || relation.isBlank()) {
            throw new IllegalArgumentException("relation is required");
        }
        Objects.requireNonNull(target, "target");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
    }

    @Override
    public int compareTo(ResourceLink other) {
        int relationOrder = relation.compareTo(other.relation);
        return relationOrder != 0 ? relationOrder : target.compareTo(other.target);
    }
}
