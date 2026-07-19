package dev.openallay.context;

import com.google.gson.JsonElement;
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
        Map<String, JsonElement> properties) {
    public RegistryEntrySnapshot {
        id = ContextValidation.identifier(id, "id");
        kind = ContextValidation.nonBlank(kind, "kind");
        displayName = ContextValidation.nonBlank(displayName, "displayName");
        namespace = ContextValidation.nonBlank(namespace, "namespace");
        provenance = ContextValidation.identifier(provenance, "provenance");
        aliases = List.copyOf(aliases);
        tags = Collections.unmodifiableSet(new TreeSet<>(tags));
        components = Collections.unmodifiableSet(new TreeSet<>(components));
        TreeMap<String, JsonElement> propertyCopy = new TreeMap<>();
        properties.forEach((key, value) -> propertyCopy.put(
                ContextValidation.identifier(key, "property key"),
                java.util.Objects.requireNonNull(value, "property value").deepCopy()));
        properties = Collections.unmodifiableMap(propertyCopy);
    }

    public RegistryEntrySnapshot(
            String id, String kind, String displayName, String namespace, String provenance) {
        this(id, kind, displayName, namespace, provenance, List.of(), Set.of(), Set.of(), Map.of());
    }

    @Override
    public Map<String, JsonElement> properties() {
        TreeMap<String, JsonElement> copy = new TreeMap<>();
        properties.forEach((key, value) -> copy.put(key, value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }
}
