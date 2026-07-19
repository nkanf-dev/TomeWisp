package dev.openallay.knowledge.online;

import dev.openallay.net.HttpCancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface OnlineKnowledgeSource {
    String sourceId();

    String provenance();

    CompletableFuture<List<RawHit>> search(
            String query, int limit, HttpCancellation cancellation);

    record RawHit(String title, String excerpt, String reference) {
        public RawHit {
            if (title == null || title.isBlank()
                    || excerpt == null
                    || reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("invalid raw online knowledge hit");
            }
        }
    }
}
