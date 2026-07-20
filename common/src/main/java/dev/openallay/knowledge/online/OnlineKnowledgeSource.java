package dev.openallay.knowledge.online;

import dev.openallay.net.HttpCancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface OnlineKnowledgeSource {
    String sourceId();

    String provenance();

    CompletableFuture<List<RawHit>> search(
            String query, int limit, HttpCancellation cancellation);

    /**
     * Reads one exact document reference previously returned by {@link #search}. Implementations
     * must still validate their fixed origin; callers must not treat this as a URL fetch surface.
     */
    default CompletableFuture<RawDocument> read(
            String reference, HttpCancellation cancellation) {
        return CompletableFuture.failedFuture(new OnlineKnowledgeException(
                "online_document_unavailable", "This source does not expose document bodies"));
    }

    record RawHit(String title, String excerpt, String reference) {
        public RawHit {
            if (title == null || title.isBlank()
                    || excerpt == null
                    || reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("invalid raw online knowledge hit");
            }
        }
    }

    record RawDocument(String title, List<RawSection> sections, String reference) {
        public RawDocument {
            if (title == null || title.isBlank()
                    || reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("invalid raw online knowledge document");
            }
            sections = List.copyOf(sections);
            if (sections.isEmpty()) {
                throw new IllegalArgumentException("online knowledge document needs a section");
            }
        }
    }

    record RawSection(String id, String heading, String text) {
        public RawSection {
            if (id == null || id.isBlank() || heading == null || heading.isBlank()
                    || text == null || text.isBlank()) {
                throw new IllegalArgumentException("invalid online knowledge section");
            }
        }
    }
}
