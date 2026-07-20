package dev.openallay.resource.mount;

import dev.openallay.context.RegistryEntrySnapshot;
import dev.openallay.context.RegistrySnapshot;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class RegistryResourceMount implements ResourceMount {
    private final String kind;
    private final Supplier<RegistrySnapshot> source;
    private long generation;

    public RegistryResourceMount(String kind, Supplier<RegistrySnapshot> source) {
        if (kind == null || kind.isBlank() || kind.indexOf(':') >= 0 || kind.indexOf('/') >= 0) {
            throw new IllegalArgumentException("kind must be a mount-safe registry kind");
        }
        this.kind = kind;
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public ResourcePath root() {
        return ResourcePath.of(kind);
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        RegistrySnapshot snapshot = Objects.requireNonNull(source.get(), "registry snapshot");
        ResourceTreeBuilder tree = new ResourceTreeBuilder(root(), snapshot.evidence());
        snapshot.entries().stream().filter(entry -> entry.kind().equals(kind)).forEach(entry -> add(tree, entry));
        return new ResourceSnapshot(root(), "registry-" + kind + '-' + ++generation,
                snapshot.evidence().capturedAt(), tree.build());
    }

    private void add(ResourceTreeBuilder tree, RegistryEntrySnapshot entry) {
        String pathPart = entry.id().substring(entry.id().indexOf(':') + 1);
        ResourcePath path = ResourcePath.of(kind, entry.namespace(), pathPart);
        LinkedHashMap<String, ResourceValue> fields = new LinkedHashMap<>();
        fields.put("id", new ResourceValue.Scalar(entry.id()));
        fields.put("kind", new ResourceValue.Scalar(entry.kind()));
        fields.put("name", new ResourceValue.Scalar(entry.displayName()));
        fields.put("namespace", new ResourceValue.Scalar(entry.namespace()));
        fields.put("provenance", new ResourceValue.Scalar(entry.provenance()));
        fields.put("aliases", ResourceValues.strings(entry.aliases()));
        fields.put("tags", ResourceValues.strings(entry.tags()));
        fields.put("components", ResourceValues.strings(entry.components()));
        LinkedHashMap<String, ResourceValue> properties = new LinkedHashMap<>();
        entry.properties().forEach((key, value) -> properties.put(key, ResourceValues.fromJson(value)));
        fields.put("properties", new ResourceValue.RecordValue(properties));
        tree.put(path, ResourceKind.RECORD, new ResourceValue.RecordValue(fields), List.of(),
                kind.equals("item")
                        ? new ResourcePresentation(ResourcePresentation.Kind.ITEM, Map.of("itemId", entry.id()))
                        : ResourcePresentation.none());

    }
}
