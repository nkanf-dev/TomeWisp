package dev.openallay.resource.mount;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceEntry;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceLink;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.query.ResourceFieldSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class ResourceTreeBuilder {
    private final ResourcePath root;
    private final EvidenceMetadata evidence;
    private final Map<ResourcePath, ResourceNode> leaves = new HashMap<>();

    ResourceTreeBuilder(ResourcePath root, EvidenceMetadata evidence) {
        this.root = root;
        this.evidence = evidence;
    }

    void put(ResourcePath path, ResourceKind kind, ResourceValue value, List<ResourceLink> links,
            ResourcePresentation presentation) {
        put(path, kind, value, links, evidence, presentation);
    }

    void put(ResourcePath path, ResourceKind kind, ResourceValue value, List<ResourceLink> links,
            EvidenceMetadata nodeEvidence, ResourcePresentation presentation) {
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside tree root: " + path);
        }
        ResourceNode previous = leaves.put(path,
                new ResourceNode(path, kind, value, List.of(), links, nodeEvidence, presentation));
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate resource path: " + path);
        }
    }

    NavigableMap<ResourcePath, ResourceNode> build() {
        addDiscoveredSchemas();
        Map<ResourcePath, List<ResourceEntry>> children = new HashMap<>();
        for (ResourceNode leaf : List.copyOf(leaves.values())) {
            ResourcePath current = leaf.path();
            while (!current.equals(root)) {
                ResourcePath parent = current.parent();
                children.computeIfAbsent(parent, ignored -> new ArrayList<>())
                        .add(new ResourceEntry(current, leaves.containsKey(current)
                                ? leaves.get(current).kind() : ResourceKind.DIRECTORY,
                                current.segments().getLast()));
                current = parent;
            }
        }
        for (ResourcePath directory : children.keySet()) {
            if (leaves.containsKey(directory)) {
                continue;
            }
            List<ResourceEntry> entries = distinct(children.get(directory));
            leaves.put(directory, new ResourceNode(directory, ResourceKind.DIRECTORY,
                    new ResourceValue.DirectoryValue(entries.size()), entries, List.of(), evidence,
                    ResourcePresentation.none()));
        }
        List<ResourceEntry> rootEntries = distinct(children.getOrDefault(root, List.of()));
        leaves.put(root, new ResourceNode(root, ResourceKind.DIRECTORY,
                new ResourceValue.DirectoryValue(rootEntries.size()), rootEntries, List.of(), evidence,
                ResourcePresentation.none()));
        // Rebuild parent nodes now that every child kind is known. Typed records retain their
        // truth while exposing trusted reserved children such as @schema.
        for (Map.Entry<ResourcePath, List<ResourceEntry>> entry : children.entrySet()) {
            ResourcePath directory = entry.getKey();
            ResourceNode existing = leaves.get(directory);
            if (existing == null) {
                continue;
            }
            List<ResourceEntry> resolved = distinct(entry.getValue().stream()
                    .map(child -> new ResourceEntry(child.path(), leaves.get(child.path()).kind(), child.label()))
                    .toList());
            ResourceValue truth = existing.kind() == ResourceKind.DIRECTORY
                    ? new ResourceValue.DirectoryValue(resolved.size()) : existing.truth();
            leaves.put(directory, new ResourceNode(
                    directory,
                    existing.kind(),
                    truth,
                    resolved,
                    existing.links(),
                    existing.evidence(),
                    existing.presentation()));
        }
        return new TreeMap<>(leaves);
    }

    private void addDiscoveredSchemas() {
        List<ResourceNode> values = List.copyOf(leaves.values());
        Map<ResourcePath, List<Map<String, ResourceValue>>> collections = new TreeMap<>();
        for (ResourceNode node : values) {
            if (!(node.truth() instanceof ResourceValue.RecordValue record)
                    || node.path().segments().getLast().startsWith("@")) {
                continue;
            }
            ResourcePath schemaPath = node.path().child("@schema");
            if (!leaves.containsKey(schemaPath)) {
                putSchema(schemaPath, ResourceFieldSchema.discover(List.of(record.fields())), node.evidence());
            }
            if (node.path().equals(root)) {
                continue;
            }
            ResourcePath current = node.path().parent();
            while (current.startsWith(root)) {
                collections.computeIfAbsent(current, ignored -> new ArrayList<>()).add(record.fields());
                if (current.equals(root)) {
                    break;
                }
                current = current.parent();
            }
        }
        collections.forEach((directory, records) -> {
            ResourcePath schemaPath = directory.child("@schema");
            if (!leaves.containsKey(schemaPath)) {
                putSchema(schemaPath, ResourceFieldSchema.discover(records), evidence);
            }
        });
    }

    private void putSchema(
            ResourcePath schemaPath, ResourceFieldSchema schema, EvidenceMetadata schemaEvidence) {
        List<ResourceValue> fields = schema.fields().stream().map(field -> {
                Map<String, ResourceValue> description = new TreeMap<>();
                description.put("path", new ResourceValue.Scalar(field.path()));
                description.put("types", new ResourceValue.ListValue(field.types().stream()
                        .map(type -> (ResourceValue) new ResourceValue.Scalar(type.name().toLowerCase()))
                        .toList()));
                description.put("present_rows", ResourceValue.Scalar.number(field.presentRows()));
                description.put("total_rows", ResourceValue.Scalar.number(field.totalRows()));
                description.put("operations", new ResourceValue.ListValue(field.operations().stream()
                        .map(operation -> (ResourceValue) new ResourceValue.Scalar(
                                operation.name().toLowerCase()))
                        .toList()));
                return (ResourceValue) new ResourceValue.RecordValue(description);
        }).toList();
        Map<String, ResourceValue> value = new TreeMap<>();
        value.put("total_rows", ResourceValue.Scalar.number(schema.totalRows()));
        value.put("fields", new ResourceValue.ListValue(fields));
        leaves.put(schemaPath, new ResourceNode(
                schemaPath,
                ResourceKind.RECORD,
                new ResourceValue.RecordValue(value),
                List.of(),
                List.of(),
                schemaEvidence,
                ResourcePresentation.none()));
    }

    private static List<ResourceEntry> distinct(List<ResourceEntry> entries) {
        TreeMap<ResourcePath, ResourceEntry> result = new TreeMap<>();
        entries.forEach(entry -> result.put(entry.path(), entry));
        return List.copyOf(result.values());
    }
}
