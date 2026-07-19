package dev.tomewisp.capability;

/** Immutable catalog card state safe for later player-facing projection. */
public record CapabilitySettingsEntry(
        String ownerId,
        String id,
        CapabilityKind kind,
        String titleKey,
        String descriptionKey,
        CapabilityChildPage childPage,
        boolean available,
        boolean enabled) {
    public CapabilitySettingsEntry {
        ownerId = CapabilitySettingsDescriptor.requireIdentity(ownerId, "owner id");
        CapabilitySettingsDescriptor descriptor = new CapabilitySettingsDescriptor(
                id, kind, titleKey, descriptionKey, childPage);
        id = descriptor.id();
        kind = descriptor.kind();
        titleKey = descriptor.titleKey();
        descriptionKey = descriptor.descriptionKey();
    }
}
