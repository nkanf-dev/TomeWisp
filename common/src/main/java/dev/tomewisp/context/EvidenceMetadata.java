package dev.tomewisp.context;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record EvidenceMetadata(
        DataAuthority authority,
        DataCompleteness completeness,
        Instant capturedAt,
        String sourceId,
        String provenance,
        String gameVersion,
        String loader,
        Map<String, String> details) {
    public EvidenceMetadata {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(completeness, "completeness");
        Objects.requireNonNull(capturedAt, "capturedAt");
        sourceId = ContextValidation.identifier(sourceId, "sourceId");
        provenance = ContextValidation.identifier(provenance, "provenance");
        gameVersion = ContextValidation.nonBlank(gameVersion, "gameVersion");
        loader = ContextValidation.nonBlank(loader, "loader");
        TreeMap<String, String> copy = new TreeMap<>();
        Objects.requireNonNull(details, "details").forEach((key, value) -> copy.put(
                ContextValidation.identifier(key, "detail key"),
                ContextValidation.nonBlank(value, "detail value")));
        details = Collections.unmodifiableMap(copy);
    }
}
