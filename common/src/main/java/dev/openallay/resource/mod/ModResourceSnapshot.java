package dev.openallay.resource.mod;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Loader-neutral, detached capture of public logical resources contributed by installed mods. */
public record ModResourceSnapshot(
        Instant capturedAt,
        Status status,
        List<ModResourceEntry> entries,
        Map<String, String> diagnostics) {
    public ModResourceSnapshot {
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(status, "status");
        TreeMap<String, String> diagnosticCopy = new TreeMap<>();
        Objects.requireNonNull(diagnostics, "diagnostics").forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("diagnostics must be non-blank");
            }
            diagnosticCopy.put(key, value);
        });
        diagnostics = Map.copyOf(diagnosticCopy);

        if (status == Status.UNAVAILABLE) {
            if (!entries.isEmpty()) {
                throw new IllegalArgumentException("Unavailable capture cannot contain resources");
            }
            entries = List.of();
        } else {
            entries = normalize(Objects.requireNonNull(entries, "entries"));
        }
    }

    public static ModResourceSnapshot available(
            Instant capturedAt, List<ModResourceEntry> entries, Map<String, String> diagnostics) {
        Status status = diagnostics.isEmpty() ? Status.AVAILABLE : Status.PARTIAL;
        return new ModResourceSnapshot(capturedAt, status, entries, diagnostics);
    }

    public static ModResourceSnapshot unavailable(Instant capturedAt, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        return new ModResourceSnapshot(capturedAt, Status.UNAVAILABLE, List.of(), Map.of("platform", reason));
    }

    public List<ModResourceEntry> activeEntries() {
        return entries.stream().filter(entry -> entry.disposition() == ModResourceEntry.Disposition.ACTIVE).toList();
    }

    private static List<ModResourceEntry> normalize(List<ModResourceEntry> candidates) {
        TreeMap<String, List<ModResourceEntry>> grouped = new TreeMap<>();
        for (ModResourceEntry entry : candidates) {
            if (entry.disposition() != ModResourceEntry.Disposition.CANDIDATE) {
                throw new IllegalArgumentException("Capture entries must be unranked candidates");
            }
            grouped.computeIfAbsent(entry.logicalIdentity(), ignored -> new ArrayList<>()).add(entry);
        }
        ArrayList<ModResourceEntry> normalized = new ArrayList<>(candidates.size());
        Comparator<ModResourceEntry> precedence = Comparator.comparingInt(ModResourceEntry::precedence)
                .reversed()
                .thenComparing(ModResourceEntry::sourceId)
                .thenComparing(ModResourceEntry::sha256);
        for (List<ModResourceEntry> values : grouped.values()) {
            values.sort(precedence);
            LinkedHashMap<String, Boolean> identities = new LinkedHashMap<>();
            for (int index = 0; index < values.size(); index++) {
                ModResourceEntry entry = values.get(index);
                String candidateIdentity = entry.precedence() + "\u0000" + entry.sourceId();
                if (identities.put(candidateIdentity, Boolean.TRUE) != null) {
                    throw new IllegalArgumentException("Duplicate logical resource candidate: " + entry.sourceId());
                }
                normalized.add(entry.withDisposition(index == 0
                        ? ModResourceEntry.Disposition.ACTIVE
                        : ModResourceEntry.Disposition.SHADOWED));
            }
        }
        return List.copyOf(normalized);
    }

    public enum Status {
        AVAILABLE, PARTIAL, UNAVAILABLE
    }
}
