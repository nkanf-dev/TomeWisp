package dev.openallay.knowledge.online;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourcePath;
import java.util.List;
import java.util.Objects;

/** Exact normalized document obtained from one fixed public knowledge origin. */
public record OnlineKnowledgeDocument(
        String sourceId,
        ResourcePath path,
        String title,
        List<Section> sections,
        String reference,
        EvidenceMetadata evidence) {
    public OnlineKnowledgeDocument {
        if (sourceId == null || sourceId.isBlank() || title == null || title.isBlank()
                || reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("invalid online knowledge document");
        }
        Objects.requireNonNull(path, "path");
        sections = List.copyOf(sections);
        if (sections.isEmpty()) throw new IllegalArgumentException("document needs a section");
        Objects.requireNonNull(evidence, "evidence");
    }

    public record Section(String id, String heading, String text) {
        public Section {
            if (id == null || id.isBlank() || heading == null || heading.isBlank()
                    || text == null || text.isBlank()) {
                throw new IllegalArgumentException("invalid online knowledge section");
            }
        }
    }
}
