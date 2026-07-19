package dev.openallay.guide.history;

public interface GuideHistoryStore extends AutoCloseable {
    GuideHistoryLoad load(GuideHistoryScope scope);

    void save(GuideHistoryPartition partition);

    default java.util.Optional<GuideHistoryMetadata> metadata(GuideHistoryScope scope) {
        throw new UnsupportedOperationException("metadata reads are unavailable");
    }

    default GuideHistoryPage page(GuideHistoryPageRequest request) {
        throw new UnsupportedOperationException("page reads are unavailable");
    }

    default GuideHistoryContextSeed context(GuideHistoryContextRequest request) {
        throw new UnsupportedOperationException("context reads are unavailable");
    }

    default void commit(GuideHistoryCommit commit) {
        throw new UnsupportedOperationException("incremental commits are unavailable");
    }

    void delete(GuideHistoryDeleteScope scope);

    void resetDatabase();

    @Override
    default void close() {}
}
