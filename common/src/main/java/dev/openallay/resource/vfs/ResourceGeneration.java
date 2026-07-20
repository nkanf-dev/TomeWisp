package dev.openallay.resource.vfs;

import java.time.Instant;
import java.util.NavigableMap;

public record ResourceGeneration(
        ResourcePath root,
        String id,
        Instant capturedAt,
        NavigableMap<ResourcePath, ResourceNode> nodes) {
    public ResourceGeneration(ResourceSnapshot snapshot) {
        this(snapshot.root(), snapshot.generationId(), snapshot.capturedAt(), snapshot.nodes());
    }

    public ResourceGeneration {
        ResourceSnapshot validated = new ResourceSnapshot(root, id, capturedAt, nodes);
        nodes = validated.nodes();
    }

    public ResourceNode require(ResourcePath path) {
        ResourceNode node = nodes.get(path);
        if (node == null) {
            throw new ResourceOperationException("resource_not_found", "Resource does not exist: " + path);
        }
        return node;
    }
}
