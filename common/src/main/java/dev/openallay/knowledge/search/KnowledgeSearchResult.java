package dev.openallay.knowledge.search;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.knowledge.KnowledgeKind;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public record KnowledgeSearchResult(
        String sourceId,
        String documentId,
        String sectionId,
        String sectionTitle,
        KnowledgeKind kind,
        String title,
        String excerpt,
        int score,
        Set<String> matchedFields,
        String provenance,
        EvidenceMetadata evidence) {
    public KnowledgeSearchResult {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (sectionId == null || sectionId.isBlank()) {
            throw new IllegalArgumentException("sectionId must not be blank");
        }
        sectionTitle = sectionTitle == null ? "" : sectionTitle;
        matchedFields = Set.copyOf(matchedFields);
        java.util.Objects.requireNonNull(evidence, "evidence");
    }

    public String documentReference() {
        return "openallay-knowledge:" + referencePart(sourceId) + "/" + referencePart(documentId);
    }

    public String sectionReference() {
        return documentReference() + "#" + referencePart(sectionId);
    }

    private static String referencePart(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
