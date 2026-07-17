package dev.tomewisp.guide.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideSessionSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class GuideHistoryRepositoryTest {
    private static final GuideHistoryScope SCOPE = GuideHistoryScope.derive(
            UUID.fromString("cd4dac2b-f49a-460f-a6bb-ddc68c921ca4"),
            GuideHistoryScope.Kind.MULTIPLAYER,
            "repository.example");

    @Test
    void serializesWorkOffCallerAndFlushesLatestSubmission() throws Exception {
        BlockingStore store = new BlockingStore();
        GuideHistoryRepository repository = new GuideHistoryRepository(store);
        long caller = Thread.currentThread().threadId();

        var first = repository.save(partition(1));
        assertTrue(store.entered.await(2, TimeUnit.SECONDS));
        var second = repository.save(partition(2));
        var flush = repository.flush();
        assertFalse(second.isDone());
        assertFalse(flush.isDone());

        store.release.countDown();
        first.join();
        second.join();
        flush.join();

        assertEquals(List.of(1L, 2L), store.savedGenerations);
        assertTrue(store.threadNames.stream().allMatch(name -> name.startsWith("tomewisp-history-")));
        assertTrue(store.threadIds.stream().allMatch(id -> id != caller));
        repository.closeAsync().join();
    }

    @Test
    void preservesStructuredFailureAndContinuesLaterWork() {
        FailingStore store = new FailingStore();
        GuideHistoryRepository repository = new GuideHistoryRepository(store);

        CompletionException failure = assertThrows(
                CompletionException.class, () -> repository.save(partition(1)).join());
        repository.save(partition(2)).join();

        GuideHistoryException history = (GuideHistoryException) failure.getCause();
        assertEquals("history_write_failed", history.code());
        assertEquals(List.of(2L), store.savedGenerations);
        repository.closeAsync().join();
    }

    @Test
    void closeDrainsPriorWorkAndRejectsNewSubmission() throws Exception {
        BlockingStore store = new BlockingStore();
        GuideHistoryRepository repository = new GuideHistoryRepository(store);
        var save = repository.save(partition(1));
        assertTrue(store.entered.await(2, TimeUnit.SECONDS));

        var close = repository.closeAsync();
        CompletionException rejected = assertThrows(
                CompletionException.class, () -> repository.load(SCOPE).join());
        assertFalse(close.isDone());

        store.release.countDown();
        save.join();
        close.join();

        assertTrue(store.closed);
        assertEquals("history_repository_closed",
                ((GuideHistoryException) rejected.getCause()).code());
    }

    private static GuideHistoryPartition partition(long generation) {
        return new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION,
                SCOPE,
                "main",
                GuideModelMode.CLIENT,
                List.of(new GuideSessionSnapshot("main", List.of(), List.of())),
                Instant.ofEpochMilli(generation));
    }

    private static final class BlockingStore implements GuideHistoryStore {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<Long> savedGenerations = new ArrayList<>();
        private final List<Long> threadIds = new ArrayList<>();
        private final List<String> threadNames = new ArrayList<>();
        private volatile boolean closed;

        @Override
        public GuideHistoryLoad load(GuideHistoryScope scope) {
            recordThread();
            return new GuideHistoryLoad(Optional.empty(), List.of());
        }

        @Override
        public void save(GuideHistoryPartition partition) {
            recordThread();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GuideHistoryException(
                        "history_write_failed", "History worker was interrupted", interrupted);
            }
            savedGenerations.add(partition.updatedAt().toEpochMilli());
        }

        @Override
        public void close() {
            recordThread();
            closed = true;
        }

        private void recordThread() {
            threadIds.add(Thread.currentThread().threadId());
            threadNames.add(Thread.currentThread().getName());
        }
    }

    private static final class FailingStore implements GuideHistoryStore {
        private final List<Long> savedGenerations = new ArrayList<>();
        private boolean first = true;

        @Override
        public GuideHistoryLoad load(GuideHistoryScope scope) {
            return GuideHistoryLoad.empty();
        }

        @Override
        public void save(GuideHistoryPartition partition) {
            if (first) {
                first = false;
                throw new GuideHistoryException(
                        "history_write_failed", "injected history failure");
            }
            savedGenerations.add(partition.updatedAt().toEpochMilli());
        }
    }
}
