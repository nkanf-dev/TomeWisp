package dev.openallay.settings.capability;

import dev.openallay.capability.CapabilityCatalogSnapshot;
import dev.openallay.capability.CapabilityPolicy;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Immutable capability-domain projection for the native settings screen. */
public record CapabilitySettingsView(
        CapabilityPolicy policy,
        CapabilityCatalogSnapshot catalog,
        Set<String> unknownDisabledTools,
        Set<String> unknownDisabledSkills) {
    public CapabilitySettingsView {
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(catalog, "catalog");
        unknownDisabledTools = sorted(unknownDisabledTools);
        unknownDisabledSkills = sorted(unknownDisabledSkills);
    }

    public static CapabilitySettingsView defaults() {
        return new CapabilitySettingsView(
                CapabilityPolicy.defaults(),
                new CapabilityCatalogSnapshot(java.util.List.of()),
                Set.of(),
                Set.of());
    }

    private static Set<String> sorted(Set<String> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }
}
