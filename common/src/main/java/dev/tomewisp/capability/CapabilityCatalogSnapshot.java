package dev.tomewisp.capability;

import java.util.List;

public record CapabilityCatalogSnapshot(List<CapabilitySettingsEntry> entries) {
    public CapabilityCatalogSnapshot {
        entries = List.copyOf(entries);
    }
}
