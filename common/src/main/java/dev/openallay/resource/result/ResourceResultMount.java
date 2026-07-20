package dev.openallay.resource.result;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceEntry;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Read-only VFS projection of one live result-store scope. */
public final class ResourceResultMount implements ResourceMount {
    private static final ResourcePath ROOT = ResourcePath.of("result");

    private final ResourceResultStore store;
    private final ResourceResultStore.Scope scope;
    private final EvidenceMetadata rootEvidence;
    private final Clock clock;
    private final Predicate<ResourcePath> visibleLink;

    public ResourceResultMount(
            ResourceResultStore store,
            ResourceResultStore.Scope scope,
            EvidenceMetadata rootEvidence) {
        this(store, scope, rootEvidence, ignored -> true, Clock.systemUTC());
    }

    public ResourceResultMount(
            ResourceResultStore store,
            ResourceResultStore.Scope scope,
            EvidenceMetadata rootEvidence,
            Predicate<ResourcePath> visibleLink) {
        this(store, scope, rootEvidence, visibleLink, Clock.systemUTC());
    }

    ResourceResultMount(
            ResourceResultStore store,
            ResourceResultStore.Scope scope,
            EvidenceMetadata rootEvidence,
            Clock clock) {
        this(store, scope, rootEvidence, ignored -> true, clock);
    }

    ResourceResultMount(
            ResourceResultStore store,
            ResourceResultStore.Scope scope,
            EvidenceMetadata rootEvidence,
            Predicate<ResourcePath> visibleLink,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.rootEvidence = Objects.requireNonNull(rootEvidence, "rootEvidence");
        this.visibleLink = Objects.requireNonNull(visibleLink, "visibleLink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ResourcePath root() {
        return ROOT;
    }

    @Override
    public ResourceSnapshot snapshot() {
        ResourceResultStore.SnapshotData data = store.snapshotData(scope);
        NavigableMap<ResourcePath, ResourceNode> nodes = new TreeMap<>();
        ArrayList<ResourceEntry> children = new ArrayList<>();
        for (ResourceResultRecord record : data.records()) {
            ResourceNode exact = record.node();
            // Lineage remains exact in ResourceResultRecord. A later request may capture a
            // different fixed mount generation, so stale cross-generation links are omitted
            // from that request's navigable projection instead of invalidating /result.
            List<dev.openallay.resource.vfs.ResourceLink> links = exact.links().stream()
                    .filter(link -> visibleLink.test(link.target()))
                    .toList();
            ResourceNode node = links.size() == exact.links().size()
                    ? exact
                    : new ResourceNode(
                            exact.path(),
                            exact.kind(),
                            exact.truth(),
                            exact.children(),
                            links,
                            exact.evidence(),
                            exact.presentation());
            nodes.put(node.path(), node);
            children.add(new ResourceEntry(node.path(), node.kind(), record.invocationId()));
        }
        nodes.put(ROOT, new ResourceNode(
                ROOT,
                ResourceKind.DIRECTORY,
                new ResourceValue.DirectoryValue(children.size()),
                List.copyOf(children),
                List.of(),
                rootEvidence,
                ResourcePresentation.none()));
        return new ResourceSnapshot(ROOT, "result-" + data.revision(), clock.instant(), nodes);
    }
}
