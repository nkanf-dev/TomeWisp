package dev.openallay.resource.mod;

import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceEntry;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceLink;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Projects detached loader captures beneath the logical {@code /mod/<modid>/raw} namespace. */
public final class ModRawMount implements ResourceMount {
    private final Supplier<ModResourceSnapshot> source;
    private final Supplier<EvidenceMetadata> evidence;
    private long generation;

    public ModRawMount(Supplier<ModResourceSnapshot> source, Supplier<EvidenceMetadata> evidence) {
        this.source = Objects.requireNonNull(source, "source");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public ResourcePath root() {
        return ResourcePath.of("mod");
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        ModResourceSnapshot capture = Objects.requireNonNull(source.get(), "mod resource snapshot");
        EvidenceMetadata baseEvidence = Objects.requireNonNull(evidence.get(), "evidence");
        TreeMap<ResourcePath, NodeSpec> specs = new TreeMap<>();
        putDirectory(specs, root());

        if (capture.status() == ModResourceSnapshot.Status.UNAVAILABLE) {
            ResourcePath statusPath = root().child("@raw-status");
            specs.put(statusPath, new NodeSpec(
                    ResourceKind.FAILURE,
                    new ResourceValue.FailureValue(
                            "resource_capture_unavailable", capture.diagnostics().getOrDefault("platform", "unavailable")),
                    List.of(),
                    ResourcePresentation.none(),
                    evidence(baseEvidence, capture.capturedAt(), DataCompleteness.UNKNOWN, Map.of(
                            "openallay:capture_status", "unavailable"))));
        } else {
            addResources(specs, capture, baseEvidence);
            if (!capture.diagnostics().isEmpty()) {
                ResourcePath statusPath = root().child("@raw-status");
                LinkedHashMap<String, ResourceValue> fields = new LinkedHashMap<>();
                fields.put("status", new ResourceValue.Scalar("partial"));
                capture.diagnostics().forEach((key, value) -> fields.put(key, new ResourceValue.Scalar(value)));
                specs.put(statusPath, new NodeSpec(
                        ResourceKind.RECORD,
                        new ResourceValue.RecordValue(fields),
                        List.of(),
                        ResourcePresentation.none(),
                        evidence(baseEvidence, capture.capturedAt(), DataCompleteness.PARTIAL, Map.of(
                                "openallay:capture_status", "partial"))));
            }
        }

        addMissingDirectories(specs);
        TreeMap<ResourcePath, ResourceNode> nodes = materialize(specs, baseEvidence, capture.capturedAt());
        String generationId = "mod-raw-" + capture.capturedAt().toEpochMilli() + '-' + ++generation;
        return new ResourceSnapshot(root(), generationId, capture.capturedAt(), nodes);
    }

    /** Returns a validated node contribution for future composite {@code /mod} mounts. */
    public synchronized Map<ResourcePath, ResourceNode> nodes() {
        return snapshot().nodes();
    }

    private void addResources(
            TreeMap<ResourcePath, NodeSpec> specs,
            ModResourceSnapshot capture,
            EvidenceMetadata baseEvidence) {
        TreeMap<String, List<ModResourceEntry>> grouped = new TreeMap<>();
        for (ModResourceEntry entry : capture.entries()) {
            grouped.computeIfAbsent(entry.logicalIdentity(), ignored -> new ArrayList<>()).add(entry);
        }
        for (List<ModResourceEntry> candidates : grouped.values()) {
            ModResourceEntry active = candidates.stream()
                    .filter(entry -> entry.disposition() == ModResourceEntry.Disposition.ACTIVE)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Logical resource has no active candidate"));
            ResourcePath canonicalPath = canonicalPath(active);
            specs.put(canonicalPath, leaf(active, canonicalPath, candidates.size() - 1, baseEvidence, capture.capturedAt()));

            ResourcePath sourceDirectory = canonicalPath.child("@source");
            putDirectory(specs, sourceDirectory);
            for (ModResourceEntry candidate : candidates) {
                ResourcePath sourcePath = sourceDirectory.child(candidate.sourceId());
                specs.put(sourcePath, leaf(candidate, sourcePath, 0, baseEvidence, capture.capturedAt()));
            }
        }
    }

    private static ResourcePath canonicalPath(ModResourceEntry entry) {
        ResourcePath raw = ResourcePath.of("mod", entry.modId(), "raw", entry.location().area().pathSegment());
        if (entry.location().area() != ModResourceEntry.Area.METADATA) {
            raw = raw.child(entry.location().namespace());
        }
        return raw.child(entry.location().logicalPath());
    }

    private static NodeSpec leaf(
            ModResourceEntry entry,
            ResourcePath path,
            int shadowedCount,
            EvidenceMetadata baseEvidence,
            Instant capturedAt) {
        Map<String, String> details = Map.of(
                "openallay:active", Boolean.toString(entry.disposition() == ModResourceEntry.Disposition.ACTIVE),
                "openallay:content_kind", entry.contentKind().name().toLowerCase(java.util.Locale.ROOT),
                "openallay:precedence", Integer.toString(entry.precedence()),
                "openallay:shadowed_count", Integer.toString(shadowedCount),
                "openallay:source", entry.sourceId());
        EvidenceMetadata entryEvidence = new EvidenceMetadata(
                baseEvidence.authority(),
                baseEvidence.completeness(),
                capturedAt,
                baseEvidence.sourceId(),
                entry.sourceId(),
                baseEvidence.gameVersion(),
                baseEvidence.loader(),
                details);
        if (entry.contentKind() == ModResourceEntry.ContentKind.BINARY_METADATA) {
            ResourceValue.BinaryMetadataValue value = new ResourceValue.BinaryMetadataValue(
                    entry.size(), entry.sha256(), entry.mediaType());
            return new NodeSpec(
                    ResourceKind.BINARY_METADATA,
                    value,
                    List.of(),
                    new ResourcePresentation(ResourcePresentation.Kind.BINARY, Map.of("resource", path.toString())),
                    entryEvidence);
        }
        String text;
        try {
            text = entry.text().orElseThrow();
        } catch (IllegalStateException exception) {
            return new NodeSpec(
                    ResourceKind.FAILURE,
                    new ResourceValue.FailureValue(
                            "resource_content_unavailable", "Public text resource is not valid UTF-8"),
                    List.of(),
                    ResourcePresentation.none(),
                    entryEvidence);
        }
        ResourceValue.DocumentValue value = new ResourceValue.DocumentValue(
                entry.location().logicalPath(),
                List.of(new ResourceValue.DocumentSection(
                        "content", entry.location().logicalPath(), text)));
        return new NodeSpec(
                ResourceKind.DOCUMENT,
                value,
                List.of(),
                new ResourcePresentation(ResourcePresentation.Kind.DOCUMENT, Map.of("resource", path.toString())),
                entryEvidence);
    }

    private static void addMissingDirectories(TreeMap<ResourcePath, NodeSpec> specs) {
        List<ResourcePath> paths = List.copyOf(specs.keySet());
        for (ResourcePath path : paths) {
            ResourcePath cursor = path;
            while (cursor.segments().size() > 1) {
                cursor = cursor.parent();
                putDirectory(specs, cursor);
            }
        }
    }

    private static TreeMap<ResourcePath, ResourceNode> materialize(
            TreeMap<ResourcePath, NodeSpec> specs,
            EvidenceMetadata baseEvidence,
            Instant capturedAt) {
        TreeMap<ResourcePath, List<ResourceEntry>> children = new TreeMap<>();
        for (Map.Entry<ResourcePath, NodeSpec> entry : specs.entrySet()) {
            ResourcePath path = entry.getKey();
            if (path.segments().size() == 1) {
                continue;
            }
            children.computeIfAbsent(path.parent(), ignored -> new ArrayList<>())
                    .add(new ResourceEntry(path, entry.getValue().kind(), path.segments().getLast()));
        }
        TreeMap<ResourcePath, ResourceNode> nodes = new TreeMap<>();
        for (Map.Entry<ResourcePath, NodeSpec> entry : specs.entrySet()) {
            ResourcePath path = entry.getKey();
            NodeSpec spec = entry.getValue();
            List<ResourceEntry> nodeChildren = children.getOrDefault(path, List.of());
            ResourceValue truth = spec.kind() == ResourceKind.DIRECTORY
                    ? new ResourceValue.DirectoryValue(nodeChildren.size())
                    : spec.truth();
            EvidenceMetadata nodeEvidence = spec.evidence() == null
                    ? evidence(baseEvidence, capturedAt, baseEvidence.completeness(), Map.of())
                    : spec.evidence();
            nodes.put(path, new ResourceNode(
                    path, spec.kind(), truth, nodeChildren, spec.links(), nodeEvidence, spec.presentation()));
        }
        return nodes;
    }

    private static EvidenceMetadata evidence(
            EvidenceMetadata base,
            Instant capturedAt,
            DataCompleteness completeness,
            Map<String, String> additionalDetails) {
        TreeMap<String, String> details = new TreeMap<>(base.details());
        details.putAll(additionalDetails);
        return new EvidenceMetadata(
                base.authority(), completeness, capturedAt, base.sourceId(), base.provenance(),
                base.gameVersion(), base.loader(), details);
    }

    private static void putDirectory(TreeMap<ResourcePath, NodeSpec> specs, ResourcePath path) {
        specs.putIfAbsent(path, new NodeSpec(
                ResourceKind.DIRECTORY,
                new ResourceValue.DirectoryValue(0),
                List.of(),
                ResourcePresentation.none(),
                null));
    }

    private record NodeSpec(
            ResourceKind kind,
            ResourceValue truth,
            List<ResourceLink> links,
            ResourcePresentation presentation,
            EvidenceMetadata evidence) {}
}
