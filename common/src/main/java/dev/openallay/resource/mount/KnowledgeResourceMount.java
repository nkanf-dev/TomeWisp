package dev.openallay.resource.mount;

import dev.openallay.knowledge.KnowledgeDocument;
import dev.openallay.knowledge.KnowledgeSnapshot;
import dev.openallay.integration.patchouli.PatchouliMultiblock;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceLink;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class KnowledgeResourceMount implements ResourceMount {
    private final String root;
    private final Supplier<KnowledgeSnapshot> source;
    private final Predicate<KnowledgeDocument> filter;
    private final PatchouliMultiblockStore multiblocks;
    private long generation;

    public KnowledgeResourceMount(Supplier<KnowledgeSnapshot> source) {
        this("knowledge", source, ignored -> true, null);
    }

    public KnowledgeResourceMount(
            Supplier<KnowledgeSnapshot> source, PatchouliMultiblockStore multiblocks) {
        this("knowledge", source, ignored -> true, multiblocks);
    }

    public KnowledgeResourceMount(
            String root, Supplier<KnowledgeSnapshot> source, Predicate<KnowledgeDocument> filter) {
        this(root, source, filter, null);
    }

    public KnowledgeResourceMount(
            String root,
            Supplier<KnowledgeSnapshot> source,
            Predicate<KnowledgeDocument> filter,
            PatchouliMultiblockStore multiblocks) {
        if (root == null || root.isBlank() || root.indexOf('/') >= 0) {
            throw new IllegalArgumentException("root is required");
        }
        this.root = root;
        this.source = Objects.requireNonNull(source, "source");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.multiblocks = multiblocks;
    }

    @Override
    public ResourcePath root() {
        return ResourcePath.of(root);
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        KnowledgeSnapshot snapshot = Objects.requireNonNull(source.get(), "knowledge snapshot");
        Map<String, PatchouliMultiblock> structureSnapshot =
                multiblocks == null ? Map.of() : multiblocks.snapshot();
        var rootEvidence = snapshot.evidence().getFirst();
        ResourceTreeBuilder tree = new ResourceTreeBuilder(root(), rootEvidence);
        snapshot.documents().stream()
                .filter(KnowledgeDocument::visible)
                .filter(filter)
                .forEach(document -> add(tree, document, structureSnapshot));
        return new ResourceSnapshot(root(), root + '-' + ++generation, snapshot.createdAt(), tree.build());
    }

    private void add(
            ResourceTreeBuilder tree,
            KnowledgeDocument document,
            Map<String, PatchouliMultiblock> structures) {
        ResourcePath path = ResourcePath.of(root, document.sourceId(), document.documentId());
        ArrayList<ResourceLink> links = new ArrayList<>();
        document.itemIds().forEach(id -> links.add(new ResourceLink("item", registryPath("item", id), id)));
        document.recipeIds().forEach(id -> links.add(new ResourceLink("recipe", registryPath("recipe", id), id)));
        if (document.structureRef() != null && !document.structureRef().isBlank()) {
            PatchouliMultiblock structure = structures.get(document.structureRef());
            if (structure != null) {
                ResourcePath structurePath = path.child("structure");
                links.add(new ResourceLink(
                        "structure", structurePath, document.structureRef()));
                tree.put(
                        structurePath,
                        ResourceKind.RECORD,
                        structureValue(structure),
                        List.of(),
                        structure.evidence(),
                        ResourcePresentation.none());
            }
        }
        ResourceValue.DocumentValue value = new ResourceValue.DocumentValue(
                document.title(),
                List.of(new ResourceValue.DocumentSection("body", document.title(), document.body())));
        tree.put(path, ResourceKind.DOCUMENT, value, links, document.evidence(),
                new ResourcePresentation(ResourcePresentation.Kind.DOCUMENT,
                        Map.of("sourceId", document.sourceId(), "documentId", document.documentId())));
    }

    private static ResourceValue.RecordValue structureValue(PatchouliMultiblock structure) {
        List<ResourceValue> blocks = structure.blocks().stream()
                .map(block -> (ResourceValue) new ResourceValue.RecordValue(Map.of(
                        "x", ResourceValue.Scalar.number(block.x()),
                        "y", ResourceValue.Scalar.number(block.y()),
                        "z", ResourceValue.Scalar.number(block.z()),
                        "state", new ResourceValue.Scalar(block.state()))))
                .toList();
        return new ResourceValue.RecordValue(Map.of(
                "id", new ResourceValue.Scalar(structure.id()),
                "block_count", ResourceValue.Scalar.number(blocks.size()),
                "blocks", new ResourceValue.ListValue(blocks),
                "provenance", new ResourceValue.Scalar(structure.provenance())));
    }

    private static ResourcePath registryPath(String mount, String id) {
        int separator = id.indexOf(':');
        return ResourcePath.of(mount, id.substring(0, separator), id.substring(separator + 1));
    }
}
