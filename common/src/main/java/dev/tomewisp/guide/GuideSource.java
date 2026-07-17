package dev.tomewisp.guide;

import dev.tomewisp.context.EvidenceMetadata;
import java.util.Objects;

public record GuideSource(String toolId, EvidenceMetadata evidence) {
    public GuideSource {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("toolId must not be blank");
        }
        Objects.requireNonNull(evidence, "evidence");
    }
}
