package dev.openallay.resource.mount;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceSnapshot;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class InstalledModResourceMount implements ResourceMount {
    private final Supplier<List<InstalledModMetadata>> source;
    private final Supplier<EvidenceMetadata> evidence;
    private long generation;

    public InstalledModResourceMount(
            Supplier<List<InstalledModMetadata>> source, Supplier<EvidenceMetadata> evidence) {
        this.source = Objects.requireNonNull(source, "source");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    @Override
    public ResourcePath root() {
        return ResourcePath.of("mod");
    }

    @Override
    public synchronized ResourceSnapshot snapshot() {
        EvidenceMetadata metadata = Objects.requireNonNull(evidence.get(), "evidence");
        ResourceTreeBuilder tree = new ResourceTreeBuilder(root(), metadata);
        for (InstalledModMetadata mod : source.get()) {
            LinkedHashMap<String, ResourceValue> fields = new LinkedHashMap<>();
            fields.put("id", new ResourceValue.Scalar(mod.id()));
            fields.put("name", new ResourceValue.Scalar(mod.name()));
            fields.put("version", new ResourceValue.Scalar(mod.version()));
            fields.put("description", new ResourceValue.Scalar(mod.description()));
            fields.put("authors", ResourceValues.strings(mod.authors()));
            fields.put("licenses", ResourceValues.strings(mod.licenses()));
            fields.put("environment", new ResourceValue.Scalar(mod.environment()));
            fields.put("dependencies", ResourceValues.strings(mod.dependencies()));
            LinkedHashMap<String, ResourceValue> contacts = new LinkedHashMap<>();
            mod.contacts().forEach((key, value) -> contacts.put(key, new ResourceValue.Scalar(value)));
            fields.put("contacts", new ResourceValue.RecordValue(contacts));
            tree.put(root().child(mod.id()), ResourceKind.RECORD, new ResourceValue.RecordValue(fields),
                    List.of(), ResourcePresentation.none());
        }
        return new ResourceSnapshot(root(), "mods-" + ++generation, metadata.capturedAt(), tree.build());
    }
}
