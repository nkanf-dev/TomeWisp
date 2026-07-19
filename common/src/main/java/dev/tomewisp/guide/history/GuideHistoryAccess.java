package dev.tomewisp.guide.history;

import java.util.concurrent.CompletableFuture;

public interface GuideHistoryAccess {
    CompletableFuture<GuideHistoryLoad> load(GuideHistoryScope scope);

    CompletableFuture<Void> save(GuideHistoryPartition partition);

    default CompletableFuture<java.util.Optional<GuideHistoryMetadata>> metadata(
            GuideHistoryScope scope) {
        return unsupported();
    }

    default CompletableFuture<GuideHistoryPage> page(GuideHistoryPageRequest request) {
        return unsupported();
    }

    default CompletableFuture<GuideHistoryContextSeed> context(GuideHistoryContextRequest request) {
        return unsupported();
    }

    default CompletableFuture<Void> commit(GuideHistoryCommit commit) {
        return unsupported();
    }

    CompletableFuture<Void> delete(GuideHistoryDeleteScope scope);

    CompletableFuture<Void> resetDatabase();

    CompletableFuture<Void> flush();

    GuideHistoryActivity activity();

    private static <T> CompletableFuture<T> unsupported() {
        return CompletableFuture.failedFuture(new GuideHistoryException(
                "history_operation_unsupported", "History operation is unavailable"));
    }
}
