package dev.openallay.resource.vfs;

import java.time.Instant;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public record ResourceSnapshot(
        ResourcePath root,
        String generationId,
        Instant capturedAt,
        NavigableMap<ResourcePath, ResourceNode> nodes) {
    public ResourceSnapshot {
        Objects.requireNonNull(root, "root");
        if (root.segments().size() != 1) {
            throw new IllegalArgumentException("Mount root must contain one segment");
        }
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("generationId is required");
        }
        Objects.requireNonNull(capturedAt, "capturedAt");
        TreeMap<ResourcePath, ResourceNode> copy = new TreeMap<>();
        Objects.requireNonNull(nodes, "nodes").forEach((path, node) -> {
            Objects.requireNonNull(path, "node path");
            Objects.requireNonNull(node, "node");
            if (!path.equals(node.path()) || !path.startsWith(root)) {
                throw new IllegalArgumentException("Resource node does not belong to mount: " + path);
            }
            copy.put(path, node);
        });
        if (!copy.containsKey(root)) {
            throw new IllegalArgumentException("Mount snapshot must include its root node");
        }
        for (ResourceNode node : copy.values()) {
            for (ResourceEntry child : node.children()) {
                if (!copy.containsKey(child.path())) {
                    throw new IllegalArgumentException("Resource child is missing: " + child.path());
                }
            }
        }
        nodes = Collections.unmodifiableNavigableMap(copy);
    }
}
