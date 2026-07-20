package dev.openallay.resource.projection;

import dev.openallay.resource.vfs.ResourcePath;
import java.util.List;

public record ResourceReceipt(
        ResourcePath resultPath,
        String generationId,
        String kind,
        long returned,
        Long total,
        List<String> fields,
        String nextCursor,
        String authority,
        String completeness,
        Long fromInclusive,
        Long toExclusive,
        List<String> recordIdentities) {
    public ResourceReceipt {
        if (kind == null || kind.isBlank() || generationId == null || generationId.isBlank() || returned < 0) {
            throw new IllegalArgumentException("Resource receipt is invalid");
        }
        if (total != null && (total < returned || total < 0)) {
            throw new IllegalArgumentException("Resource receipt total is invalid");
        }
        fields = List.copyOf(fields);
        if (authority == null || authority.isBlank() || completeness == null || completeness.isBlank()) {
            throw new IllegalArgumentException("Resource receipt evidence is required");
        }
        if ((fromInclusive == null) != (toExclusive == null)
                || fromInclusive != null && (fromInclusive < 0 || toExclusive < fromInclusive)) {
            throw new IllegalArgumentException("Resource receipt range is invalid");
        }
        recordIdentities = List.copyOf(recordIdentities);
    }

    /** Compatibility constructor for non-VFS transitional projections. */
    public ResourceReceipt(
            ResourcePath resultPath,
            String generationId,
            String kind,
            long returned,
            Long total,
            List<String> fields,
            String nextCursor) {
        this(resultPath, generationId, kind, returned, total, fields, nextCursor,
                "unknown", "unknown", null, null, List.of());
    }
}
