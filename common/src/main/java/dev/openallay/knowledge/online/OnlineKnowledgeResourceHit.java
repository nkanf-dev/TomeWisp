package dev.openallay.knowledge.online;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourcePath;
import java.util.Objects;

/** Search hit with an opaque VFS path that can be read only in the discovering request. */
public record OnlineKnowledgeResourceHit(
        String sourceId,
        String title,
        String excerpt,
        ResourcePath documentPath,
        EvidenceMetadata evidence) {
    public OnlineKnowledgeResourceHit {
        if (sourceId == null || sourceId.isBlank() || title == null || title.isBlank()
                || excerpt == null) {
            throw new IllegalArgumentException("invalid online knowledge resource hit");
        }
        Objects.requireNonNull(documentPath, "documentPath");
        Objects.requireNonNull(evidence, "evidence");
    }
}
