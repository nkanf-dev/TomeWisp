package dev.openallay.resource.vfs;

import dev.openallay.context.EvidenceMetadata;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Publishes several contributors that own one logical root as one atomic mount generation.
 *
 * <p>This is primarily used by {@code /mod}: installed-mod metadata owns the resource at
 * {@code /mod/<modid>} while the generic resource capture contributes its {@code raw} children.
 * Callers register only this composite with the mount registry, so a request can never observe
 * one contribution without the other.</p>
 */
public final class CompositeResourceMount implements ResourceMount {
    private final ResourcePath root;
    private final List<ResourceMount> contributors;
    private long generation;

    public CompositeResourceMount(ResourcePath root, List<? extends ResourceMount> contributors) {
        this.root = Objects.requireNonNull(root, "root");
        if (root.segments().size() != 1) {
            throw new IllegalArgumentException("Composite mount root must contain one segment");
        }
        this.contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors"));
        if (this.contributors.isEmpty()) {
            throw new IllegalArgumentException("Composite mount requires at least one contributor");
        }
        for (ResourceMount contributor : this.contributors) {
            if (!root.equals(Objects.requireNonNull(contributor, "contributor").root())) {
                throw new IllegalArgumentException("Composite contributor owns a different mount root");
            }
        }
    }

    @Override
    public ResourcePath root() {
        return root;
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        ArrayList<ResourceSnapshot> snapshots = new ArrayList<>(contributors.size());
        for (ResourceMount contributor : contributors) {
            ResourceSnapshot snapshot = Objects.requireNonNull(contributor.snapshot(), "contributor snapshot");
            if (!root.equals(snapshot.root())) {
                throw new IllegalArgumentException("Composite contributor snapshot owns a different root");
            }
            snapshots.add(snapshot);
        }

        TreeMap<ResourcePath, ResourceNode> merged = new TreeMap<>();
        for (ResourceSnapshot snapshot : snapshots) {
            snapshot.nodes().forEach((path, node) -> merged.merge(path, node, CompositeResourceMount::mergeNode));
        }
        rebuildChildren(merged);
        Instant capturedAt = snapshots.stream()
                .map(ResourceSnapshot::capturedAt)
                .max(Instant::compareTo)
                .orElseThrow();
        String sourceGenerations = snapshots.stream()
                .map(ResourceSnapshot::generationId)
                .reduce((left, right) -> left + '+' + right)
                .orElseThrow();
        return new ResourceSnapshot(
                root,
                "composite-" + ++generation + '-' + Integer.toUnsignedString(sourceGenerations.hashCode(), 36),
                capturedAt,
                merged);
    }

    private static ResourceNode mergeNode(ResourceNode left, ResourceNode right) {
        if (!left.path().equals(right.path())) {
            throw new IllegalArgumentException("Cannot merge different resource paths");
        }
        ResourceNode selected;
        if (left.kind() == ResourceKind.DIRECTORY && right.kind() != ResourceKind.DIRECTORY) {
            selected = right;
        } else if (right.kind() == ResourceKind.DIRECTORY && left.kind() != ResourceKind.DIRECTORY) {
            selected = left;
        } else if (left.kind() == ResourceKind.DIRECTORY) {
            selected = left;
        } else {
            if (left.kind() != right.kind() || !left.truth().equals(right.truth())) {
                throw new IllegalArgumentException("Conflicting composite resource: " + left.path());
            }
            selected = left;
        }

        LinkedHashSet<ResourceLink> links = new LinkedHashSet<>(left.links());
        links.addAll(right.links());
        ResourcePresentation presentation = selected.presentation().kind() == ResourcePresentation.Kind.NONE
                ? other(selected, left, right).presentation()
                : selected.presentation();
        EvidenceMetadata evidence = selected.evidence();
        return new ResourceNode(
                selected.path(),
                selected.kind(),
                selected.truth(),
                List.of(),
                List.copyOf(links),
                evidence,
                presentation);
    }

    private static ResourceNode other(ResourceNode selected, ResourceNode left, ResourceNode right) {
        return selected == left ? right : left;
    }

    private static void rebuildChildren(TreeMap<ResourcePath, ResourceNode> nodes) {
        TreeMap<ResourcePath, List<ResourceEntry>> children = new TreeMap<>();
        for (ResourceNode node : nodes.values()) {
            if (node.path().segments().size() == 1) {
                continue;
            }
            children.computeIfAbsent(node.path().parent(), ignored -> new ArrayList<>())
                    .add(new ResourceEntry(
                            node.path(), node.kind(), node.path().segments().getLast()));
        }
        for (Map.Entry<ResourcePath, ResourceNode> entry : List.copyOf(nodes.entrySet())) {
            ResourceNode node = entry.getValue();
            List<ResourceEntry> nodeChildren = children.getOrDefault(node.path(), List.of());
            ResourceValue truth = node.kind() == ResourceKind.DIRECTORY
                    ? new ResourceValue.DirectoryValue(nodeChildren.size())
                    : node.truth();
            nodes.put(node.path(), new ResourceNode(
                    node.path(), node.kind(), truth, nodeChildren, node.links(), node.evidence(), node.presentation()));
        }
    }
}
