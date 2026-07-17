package dev.tomewisp.guide.history;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Ordered asynchronous boundary around the synchronous durable store. */
public final class GuideHistoryRepository {
    private final GuideHistoryStore store;
    private final ExecutorService worker;
    private CompletableFuture<Void> latest = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> closeFuture;
    private boolean closing;

    public GuideHistoryRepository(GuideHistoryStore store) {
        this(
                store,
                Executors.newSingleThreadExecutor(Thread.ofPlatform()
                        .name("tomewisp-history-", 0)
                        .daemon(true)
                        .factory()));
    }

    GuideHistoryRepository(GuideHistoryStore store, ExecutorService worker) {
        this.store = Objects.requireNonNull(store, "store");
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    public CompletableFuture<GuideHistoryLoad> load(GuideHistoryScope scope) {
        Objects.requireNonNull(scope, "scope");
        return submit(
                "history_load_failed",
                "Unable to load durable guide history",
                () -> store.load(scope));
    }

    public CompletableFuture<Void> save(GuideHistoryPartition partition) {
        Objects.requireNonNull(partition, "partition");
        return submit(
                "history_write_failed",
                "Unable to save durable guide history",
                () -> {
                    store.save(partition);
                    return null;
                });
    }

    public synchronized CompletableFuture<Void> flush() {
        return latest;
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) {
            return closeFuture;
        }
        closing = true;
        CompletableFuture<Void> closingTask = CompletableFuture.runAsync(store::close, worker);
        latest = closingTask.handle((ignored, failure) -> null);
        closeFuture = closingTask.whenComplete((ignored, failure) -> worker.shutdown());
        return closeFuture;
    }

    private synchronized <T> CompletableFuture<T> submit(
            String failureCode,
            String failureMessage,
            Supplier<T> operation) {
        if (closing) {
            return CompletableFuture.failedFuture(new GuideHistoryException(
                    "history_repository_closed", "Guide history repository is closed"));
        }
        CompletableFuture<T> future = CompletableFuture.supplyAsync(
                () -> guarded(failureCode, failureMessage, operation), worker);
        latest = future.handle((ignored, failure) -> null);
        return future;
    }

    private static <T> T guarded(
            String failureCode,
            String failureMessage,
            Supplier<T> operation) {
        try {
            return operation.get();
        } catch (GuideHistoryException known) {
            throw known;
        } catch (RuntimeException unexpected) {
            throw new GuideHistoryException(failureCode, failureMessage, unexpected);
        }
    }
}
