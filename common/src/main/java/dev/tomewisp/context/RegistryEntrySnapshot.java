package dev.tomewisp.context;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record RegistryEntrySnapshot(
        String id,
        String kind,
        String displayName,
        String namespace,
        String provenance,
        List<String> aliases,
        Set<String> tags,
        Set<String> components,
        Map<String, String> metadata) {
    public RegistryEntrySnapshot {
        id = ContextValidation.identifier(id, "id");
        kind = ContextValidation.nonBlank(kind, "kind");
        displayName = ContextValidation.nonBlank(displayName, "displayName");
        namespace = ContextValidation.nonBlank(namespace, "namespace");
        provenance = ContextValidation.identifier(provenance, "provenance");
        aliases = List.copyOf(aliases);
        tags = Collections.unmodifiableSet(new TreeSet<>(tags));
        components = Collections.unmodifiableSet(new TreeSet<>(components));
        TreeMap<String, String> metadataCopy = new TreeMap<>();
        metadata.forEach((key, value) -> metadataCopy.put(
                ContextValidation.nonBlank(key, "metadata key"),
                ContextValidation.nonBlank(value, "metadata value")));
        metadata = Collections.unmodifiableMap(metadataCopy);
    }

    public RegistryEntrySnapshot(
            String id, String kind, String displayName, String namespace, String provenance) {
        this(id, kind, displayName, namespace, provenance, List.of(), Set.of(), Set.of(), Map.of());
    }
}
