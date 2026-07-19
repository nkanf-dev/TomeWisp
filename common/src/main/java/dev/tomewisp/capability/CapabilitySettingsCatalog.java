package dev.tomewisp.capability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Trusted registration authority for presentation-only capability descriptors. */
public final class CapabilitySettingsCatalog {
    private static final Comparator<Registration> ORDER = Comparator
            .comparing((Registration value) -> value.descriptor().kind())
            .thenComparing(value -> value.descriptor().id());

    private final Map<String, Registration> registrations = new TreeMap<>();

    public synchronized void register(
            String ownerId, Collection<CapabilitySettingsDescriptor> descriptors) {
        String owner = CapabilitySettingsDescriptor.requireIdentity(ownerId, "owner id");
        List<CapabilitySettingsDescriptor> candidate = List.copyOf(descriptors);
        Set<String> batchIds = new HashSet<>();
        for (CapabilitySettingsDescriptor descriptor : candidate) {
            Objects.requireNonNull(descriptor, "descriptor");
            if (!batchIds.add(descriptor.id()) || registrations.containsKey(descriptor.id())) {
                throw new IllegalStateException(
                        "Duplicate capability settings id " + descriptor.id());
            }
        }
        candidate.forEach(descriptor ->
                registrations.put(descriptor.id(), new Registration(owner, descriptor)));
    }

    public synchronized CapabilityCatalogSnapshot snapshot(CapabilityCatalogState state) {
        Objects.requireNonNull(state, "state");
        List<Registration> ordered = registrations.values().stream().sorted(ORDER).toList();
        List<CapabilitySettingsEntry> entries = new ArrayList<>(ordered.size());
        for (Registration registration : ordered) {
            CapabilitySettingsDescriptor descriptor = registration.descriptor();
            entries.add(new CapabilitySettingsEntry(
                    registration.ownerId(),
                    descriptor.id(),
                    descriptor.kind(),
                    descriptor.titleKey(),
                    descriptor.descriptionKey(),
                    descriptor.childPage(),
                    !state.unavailableIds().contains(descriptor.id()),
                    !state.disabledIds().contains(descriptor.id())));
        }
        return new CapabilityCatalogSnapshot(entries);
    }

    private record Registration(
            String ownerId, CapabilitySettingsDescriptor descriptor) {}
}
