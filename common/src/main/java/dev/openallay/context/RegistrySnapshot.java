package dev.openallay.context;

import java.util.List;

public record RegistrySnapshot(EvidenceMetadata evidence, List<RegistryEntrySnapshot> entries) {
    public RegistrySnapshot {
        java.util.Objects.requireNonNull(evidence, "evidence");
        entries = List.copyOf(entries);
    }
}
