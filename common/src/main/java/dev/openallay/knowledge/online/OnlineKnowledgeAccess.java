package dev.openallay.knowledge.online;

import dev.openallay.model.CancellationSignal;
import dev.openallay.resource.vfs.ResourcePath;
import java.util.concurrent.CompletableFuture;

/** Request-scoped, fixed-origin online knowledge capability exposed behind VFS operations. */
public interface OnlineKnowledgeAccess extends AutoCloseable {
    ResourcePath ROOT = ResourcePath.of("knowledge", "online");

    CompletableFuture<OnlineKnowledgeResourceSearch> search(
            String query, CancellationSignal cancellation);

    CompletableFuture<OnlineKnowledgeDocument> read(
            ResourcePath path, CancellationSignal cancellation);

    default boolean owns(ResourcePath path) {
        return path != null && path.startsWith(ROOT) && path.segments().size() >= 4;
    }

    @Override
    default void close() {}

    static OnlineKnowledgeAccess unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements OnlineKnowledgeAccess {
        INSTANCE;

        @Override
        public CompletableFuture<OnlineKnowledgeResourceSearch> search(
                String query, CancellationSignal cancellation) {
            return CompletableFuture.completedFuture(new OnlineKnowledgeResourceSearch(
                    java.util.List.of(),
                    java.util.List.of(new OnlineKnowledgeDiagnostic(
                            "openallay:online_knowledge", "online_source_unavailable",
                            "Public knowledge sources are unavailable"))));
        }

        @Override
        public CompletableFuture<OnlineKnowledgeDocument> read(
                ResourcePath path, CancellationSignal cancellation) {
            return CompletableFuture.failedFuture(new OnlineKnowledgeException(
                    "online_document_not_discovered",
                    "The document is not available in this Agent request"));
        }
    }
}
