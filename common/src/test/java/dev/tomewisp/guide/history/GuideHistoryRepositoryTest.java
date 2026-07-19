package dev.tomewisp.guide.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.GuideSessionSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
        assertTrue(repository.activity().idleForDeletion());
        repository.delete(GuideHistoryDeleteScope.actor(SCOPE.actorId())).join();
        assertEquals(List.of(2L), store.savedGenerations);
        assertEquals(1, store.deleteCalls);
        repository.closeAsync().join();
    }

    @Test
    void deleteRejectsInsteadOfQueueingBehindPendingSave() throws Exception {
        BlockingStore store = new BlockingStore();
        GuideHistoryRepository repository = new GuideHistoryRepository(store);
        CompletableFuture<Void> save = repository.save(partition(1));
        assertTrue(store.entered.await(2, TimeUnit.SECONDS));

        assertEquals(new GuideHistoryActivity(1, false), repository.activity());
        assertFutureCode(
                repository.delete(GuideHistoryDeleteScope.actor(SCOPE.actorId())),
                "history_delete_busy");
        assertFutureCode(repository.resetDatabase(), "history_delete_busy");
        assertEquals(0, store.deleteCalls);
        assertEquals(0, store.resetCalls);

        store.release.countDown();
        save.join();
        assertTrue(repository.activity().idleForDeletion());
        repository.closeAsync().join();
    }

    @Test
    void reservedDeleteBlocksNewSaveAndCannotBeResurrected() throws Exception {
        DeletingStore store = new DeletingStore(false);
        GuideHistoryRepository repository = new GuideHistoryRepository(store);

        CompletableFuture<Void> deleting = repository.delete(
                GuideHistoryDeleteScope.partition(SCOPE));
        assertTrue(store.entered.await(2, TimeUnit.SECONDS));

        assertEquals(new GuideHistoryActivity(0, true), repository.activity());
        assertFutureCode(repository.metadata(SCOPE), "history_delete_busy");
        assertFutureCode(repository.save(partition(1)), "history_delete_busy");
        assertFutureCode(
                repository.delete(GuideHistoryDeleteScope.actor(SCOPE.actorId())),
                "history_delete_busy");
        assertFutureCode(repository.resetDatabase(), "history_delete_busy");
        assertTrue(store.savedGenerations.isEmpty());

        store.release.countDown();
        deleting.join();
        assertTrue(repository.activity().idleForDeletion());
        repository.save(partition(2)).join();
        assertEquals(List.of(2L), store.savedGenerations);
        repository.closeAsync().join();
    }

    @Test
    void resetUsesTheSameReservationAndCloseDrainsIt() throws Exception {
        DeletingStore store = new DeletingStore(true);
        GuideHistoryRepository repository = new GuideHistoryRepository(store);
        CompletableFuture<Void> resetting = repository.resetDatabase();
        assertTrue(store.entered.await(2, TimeUnit.SECONDS));

        CompletableFuture<Void> close = repository.closeAsync();
        assertFutureCode(repository.load(SCOPE), "history_repository_closed");
        assertFalse(close.isDone());

        store.release.countDown();
        resetting.join();
        close.join();
        assertEquals(1, store.resetCalls);
        assertTrue(store.closed);
    }

    @Test
    void cancellingReturnedSaveFutureDoesNotReleaseReservationOrFlushEarly() throws Exception {
        BlockingStore store = new BlockingStore();
        GuideHistoryRepository repository = new GuideHistoryRepository(store);
        CompletableFuture<Void> save = repository.save(partition(1));
        assertTrue(store.entered.await(2, TimeUnit.SECONDS));

        assertTrue(save.cancel(false));
        assertEquals(new GuideHistoryActivity(1, false), repository.activity());
        assertFalse(repository.flush().isDone());
        assertFutureCode(
                repository.delete(GuideHistoryDeleteScope.actor(SCOPE.actorId())),
                "history_delete_busy");

        store.release.countDown();
        repository.flush().join();
        assertTrue(repository.activity().idleForDeletion());
        repository.closeAsync().join();
    }

    @Test
    void cancellingReturnedDeleteFutureDoesNotAllowAResurrectionSave() throws Exception {
        DeletingStore store = new DeletingStore(false);
        GuideHistoryRepository repository = new GuideHistoryRepository(store);
        CompletableFuture<Void> deleting = repository.delete(
                GuideHistoryDeleteScope.partition(SCOPE));
        assertTrue(store.entered.await(2, TimeUnit.SECONDS));

        assertTrue(deleting.cancel(false));
        assertEquals(new GuideHistoryActivity(0, true), repository.activity());
        assertFalse(repository.flush().isDone());
        assertFutureCode(repository.save(partition(1)), "history_delete_busy");

        store.release.countDown();
        repository.flush().join();
        assertTrue(repository.activity().idleForDeletion());
        assertTrue(store.savedGenerations.isEmpty());
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

    @Test
    void incrementalCommitsAndWindowReadsShareOneOrderedWorker() throws Exception {
        WindowStore store = new WindowStore();
        GuideHistoryRepository repository = new GuideHistoryRepository(store);
        long caller = Thread.currentThread().threadId();
        GuideHistoryCommit commit = new GuideHistoryCommit(
                SCOPE, List.of(new GuideHistoryMutation.UpsertPartition(
                        "main", Instant.EPOCH)));

        CompletableFuture<Void> writing = repository.commit(commit);
        assertTrue(store.entered.await(2, TimeUnit.SECONDS));
        CompletableFuture<Optional<GuideHistoryMetadata>> metadata =
                repository.metadata(SCOPE);
        assertFalse(metadata.isDone());
        assertEquals(new GuideHistoryActivity(1, false), repository.activity());

        store.release.countDown();
        writing.join();
        assertTrue(metadata.join().isEmpty());
        assertEquals(List.of("commit", "metadata"), store.operations);
        assertTrue(store.threadIds.stream().allMatch(id -> id != caller));
        repository.closeAsync().join();
    }

    private static GuideHistoryPartition partition(long generation) {
        return new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION,
                SCOPE,
                "main",
                List.of(new GuideSessionSnapshot("main", List.of(), List.of())),
                Instant.ofEpochMilli(generation));
    }

    private static void assertFutureCode(CompletableFuture<?> future, String code) {
        CompletionException failure = assertThrows(CompletionException.class, future::join);
        assertEquals(code, ((GuideHistoryException) failure.getCause()).code());
    }

    private static final class BlockingStore implements GuideHistoryStore {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<Long> savedGenerations = new ArrayList<>();
        private final List<Long> threadIds = new ArrayList<>();
        private final List<String> threadNames = new ArrayList<>();
        private int deleteCalls;
        private int resetCalls;
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
        public void delete(GuideHistoryDeleteScope scope) {
            deleteCalls++;
        }

        @Override
        public void resetDatabase() {
            resetCalls++;
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
        private int deleteCalls;

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

        @Override
        public void delete(GuideHistoryDeleteScope scope) {
            deleteCalls++;
        }

        @Override
        public void resetDatabase() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class DeletingStore implements GuideHistoryStore {
        private final boolean reset;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<Long> savedGenerations = new ArrayList<>();
        private int deleteCalls;
        private int resetCalls;
        private volatile boolean closed;

        private DeletingStore(boolean reset) {
            this.reset = reset;
        }

        @Override
        public GuideHistoryLoad load(GuideHistoryScope scope) {
            return GuideHistoryLoad.empty();
        }

        @Override
        public void save(GuideHistoryPartition partition) {
            savedGenerations.add(partition.updatedAt().toEpochMilli());
        }

        @Override
        public void delete(GuideHistoryDeleteScope scope) {
            if (reset) {
                throw new AssertionError("delete was called instead of reset");
            }
            deleteCalls++;
            block();
        }

        @Override
        public void resetDatabase() {
            if (!reset) {
                throw new AssertionError("reset was called instead of delete");
            }
            resetCalls++;
            block();
        }

        @Override
        public void close() {
            closed = true;
        }

        private void block() {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GuideHistoryException(
                        "history_delete_failed", "History worker was interrupted", interrupted);
            }
        }
    }

    private static final class WindowStore implements GuideHistoryStore {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> operations = new ArrayList<>();
        private final List<Long> threadIds = new ArrayList<>();

        @Override
        public GuideHistoryLoad load(GuideHistoryScope scope) {
            return GuideHistoryLoad.empty();
        }

        @Override
        public void save(GuideHistoryPartition partition) {
            throw new AssertionError("full save is not expected");
        }

        @Override
        public void commit(GuideHistoryCommit commit) {
            operations.add("commit");
            threadIds.add(Thread.currentThread().threadId());
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GuideHistoryException(
                        "history_write_failed", "interrupted", interrupted);
            }
        }

        @Override
        public Optional<GuideHistoryMetadata> metadata(GuideHistoryScope scope) {
            operations.add("metadata");
            threadIds.add(Thread.currentThread().threadId());
            return Optional.empty();
        }

        @Override public void delete(GuideHistoryDeleteScope scope) {}
        @Override public void resetDatabase() {}
    }
}
