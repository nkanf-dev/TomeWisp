package dev.openallay.resource.mount;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.knowledge.online.OnlineKnowledgeSearchService;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValues;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed catalog for online knowledge origins; network documents are discovered per request. */
public final class OnlineKnowledgeCatalogMount implements ResourceMount {
    private final List<OnlineKnowledgeSearchService.SourceDescriptor> sources;
    private final EvidenceMetadata evidence;
    private long generation;

    public OnlineKnowledgeCatalogMount(
            List<OnlineKnowledgeSearchService.SourceDescriptor> sources,
            EvidenceMetadata evidence) {
        this.sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public ResourcePath root() {
        return ResourcePath.of("knowledge");
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        ResourceTreeBuilder tree = new ResourceTreeBuilder(root(), evidence);
        for (OnlineKnowledgeSearchService.SourceDescriptor source : sources) {
            ResourcePath path = ResourcePath.of(
                    "knowledge", "online", source.pathSegment(), "@source");
            tree.put(
                    path,
                    ResourceKind.RECORD,
                    ResourceValues.record(Map.of(
                            "source_id", source.sourceId(),
                            "provenance", source.provenance(),
                            "access", "search with resource_grep; read returned document paths with resource_read")),
                    List.of(),
                    evidence,
                    ResourcePresentation.none());
        }
        return new ResourceSnapshot(
                root(), "online-catalog-" + ++generation, evidence.capturedAt(), tree.build());
    }
}
