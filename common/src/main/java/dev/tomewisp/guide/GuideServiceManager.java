package dev.tomewisp.guide;

import com.google.gson.Gson;
import dev.tomewisp.client.ClientEventDispatcher;
import dev.tomewisp.guide.history.GuideHistoryAccess;
import dev.tomewisp.guide.history.GuideHistoryScope;
import dev.tomewisp.guide.history.GuideHistoryScopeProvider;
import dev.tomewisp.tool.ToolResult;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Creates exactly one connection-scoped GuideService for the current client actor. */
public final class GuideServiceManager {
    private final GuideLocalEndpoint local;
    private final GuideRemoteEndpoint remote;
    private final GuideContextProvider contexts;
    private final ClientEventDispatcher dispatcher;
    private final Clock clock;
    private final Gson gson;
    private final GuideHistoryAccess history;
    private final GuideHistoryScopeProvider historyScopes;
    private GuideService current;

    public GuideServiceManager(
            GuideLocalEndpoint local,
            GuideRemoteEndpoint remote,
            GuideContextProvider contexts,
            ClientEventDispatcher dispatcher,
            Clock clock,
            Gson gson) {
        this(local, remote, contexts, dispatcher, clock, gson, null, null);
    }

    public GuideServiceManager(
            GuideLocalEndpoint local,
            GuideRemoteEndpoint remote,
            GuideContextProvider contexts,
            ClientEventDispatcher dispatcher,
            Clock clock,
            Gson gson,
            GuideHistoryAccess history,
            GuideHistoryScopeProvider historyScopes) {
        this.local = local;
        this.remote = Objects.requireNonNull(remote, "remote");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.gson = Objects.requireNonNull(gson, "gson");
        if ((history == null) != (historyScopes == null)) {
            throw new IllegalArgumentException("history access and scope provider must be configured together");
        }
        this.history = history;
        this.historyScopes = historyScopes;
    }

    public synchronized GuideService forActor(UUID actor) {
        GuideHistoryScope scope = historyScopes == null ? null : historyScopes.resolve(actor);
        if (current == null
                || !current.snapshot().actorId().equals(actor)
                || !Objects.equals(current.historyScope(), scope)) {
            GuideHistoryAccess nextHistory = history;
            if (current != null) {
                CompletableFuture<Void> disconnected = current.disconnect();
                if (history != null) {
                    nextHistory = afterDisconnect(disconnected);
                }
            }
            current = new GuideService(
                    actor, local, remote, contexts, dispatcher, clock, gson, scope, nextHistory);
        }
        return current;
    }

    public synchronized CompletableFuture<Void> disconnect() {
        if (current != null) {
            CompletableFuture<Void> disconnected = current.disconnect();
            current = null;
            return disconnected;
        }
        remote.disconnect();
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> shutdown() {
        return disconnect();
    }

    public synchronized GuideService current() {
        return current;
    }

    public synchronized GuideHistorySettingsSnapshot historySettingsSnapshot() {
        if (history == null || current == null || current.historyScope() == null) {
            return GuideHistorySettingsSnapshot.unavailable(history != null);
        }
        return new GuideHistorySettingsSnapshot(
                true,
                java.util.Optional.of(current.snapshot()),
                history.activity(),
                java.util.Optional.of(current.historyScope().kind()));
    }

    public synchronized CompletableFuture<ToolResult<Boolean>> resetHistoryDatabase() {
        if (current == null) {
            return CompletableFuture.completedFuture(new ToolResult.Failure<>(
                    "history_unavailable", "Durable guide history is unavailable"));
        }
        return current.resetHistoryDatabase();
    }

    private GuideHistoryAccess afterDisconnect(CompletableFuture<Void> disconnected) {
        return new GuideHistoryAccess() {
            @Override
            public CompletableFuture<dev.tomewisp.guide.history.GuideHistoryLoad> load(
                    GuideHistoryScope scope) {
                return disconnected.thenCompose(ignored -> history.load(scope));
            }

            @Override
            public CompletableFuture<Void> save(
                    dev.tomewisp.guide.history.GuideHistoryPartition partition) {
                return disconnected.thenCompose(ignored -> history.save(partition));
            }

            @Override
            public CompletableFuture<java.util.Optional<
                    dev.tomewisp.guide.history.GuideHistoryMetadata>> metadata(
                    GuideHistoryScope scope) {
                return disconnected.thenCompose(ignored -> history.metadata(scope));
            }

            @Override
            public CompletableFuture<dev.tomewisp.guide.history.GuideHistoryPage> page(
                    dev.tomewisp.guide.history.GuideHistoryPageRequest request) {
                return disconnected.thenCompose(ignored -> history.page(request));
            }

            @Override
            public CompletableFuture<dev.tomewisp.guide.history.GuideHistoryContextSeed> context(
                    dev.tomewisp.guide.history.GuideHistoryContextRequest request) {
                return disconnected.thenCompose(ignored -> history.context(request));
            }

            @Override
            public CompletableFuture<Void> commit(
                    dev.tomewisp.guide.history.GuideHistoryCommit commit) {
                return disconnected.thenCompose(ignored -> history.commit(commit));
            }

            @Override
            public CompletableFuture<Void> delete(
                    dev.tomewisp.guide.history.GuideHistoryDeleteScope scope) {
                return disconnected.thenCompose(ignored -> history.delete(scope));
            }

            @Override
            public CompletableFuture<Void> resetDatabase() {
                return disconnected.thenCompose(ignored -> history.resetDatabase());
            }

            @Override
            public CompletableFuture<Void> flush() {
                return disconnected.thenCompose(ignored -> history.flush());
            }

            @Override
            public dev.tomewisp.guide.history.GuideHistoryActivity activity() {
                return disconnected.isDone()
                        ? history.activity()
                        : new dev.tomewisp.guide.history.GuideHistoryActivity(1, false);
            }
        };
    }
}
