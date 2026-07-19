package dev.tomewisp.capability;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Detached availability and deny state projected onto trusted descriptors. */
public record CapabilityCatalogState(
        Set<String> unavailableIds,
        Set<String> disabledIds) {
    public CapabilityCatalogState {
        unavailableIds = canonical(unavailableIds, "unavailable capability id");
        disabledIds = canonical(disabledIds, "disabled capability id");
    }

    public static CapabilityCatalogState defaults() {
        return new CapabilityCatalogState(Set.of(), Set.of());
    }

    private static Set<String> canonical(Set<String> values, String name) {
        if (values == null) {
            throw new NullPointerException(name);
        }
        TreeSet<String> result = new TreeSet<>();
        for (String value : values) {
            result.add(CapabilitySettingsDescriptor.requireIdentity(value, name));
        }
        return Collections.unmodifiableSet(result);
    }
}
