package dev.openallay.guide;

import com.google.gson.Gson;
import dev.openallay.agent.AgentEvent;
import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.client.ClientEventDispatcher;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.guide.history.GuideHistoryAccess;
import dev.openallay.guide.history.GuideHistoryDeleteScope;
import dev.openallay.guide.history.GuideHistoryException;
import dev.openallay.guide.history.GuideHistoryLoad;
import dev.openallay.guide.history.GuideHistoryPartition;
import dev.openallay.guide.history.GuideHistoryMetadata;
import dev.openallay.guide.history.GuideHistoryPage;
import dev.openallay.guide.history.GuideHistoryPageRequest;
import dev.openallay.guide.history.GuideHistoryCursor;
import dev.openallay.guide.history.GuideHistoryCommit;
import dev.openallay.guide.history.GuideHistoryContextRequest;
import dev.openallay.guide.history.GuideHistoryContextSeed;
import dev.openallay.guide.history.GuideHistoryMutation;
import dev.openallay.guide.history.GuideHistoryScope;
import dev.openallay.guide.export.GuideSessionExportCollector;
import dev.openallay.guide.export.GuideSessionExportSnapshot;
import dev.openallay.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Single client product state source for commands and screens. */
public final class GuideService implements GuideHistoryAdministration {
    private final UUID actor;
    private final GuideLocalEndpoint local;
    private final GuideRemoteEndpoint remote;
    private final GuideContextProvider contexts;
    private final ClientEventDispatcher dispatcher;
    private final Clock clock;
    private final GuideStateReducer reducer;
    private final GuideHistoryScope historyScope;
    private final GuideHistoryAccess history;
    private final Map<String, SessionState> sessions = new LinkedHashMap<>();
    private final Map<UUID, String> requestSessions = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<GuideSnapshot>> listeners =
            new CopyOnWriteArrayList<>();
    private volatile GuideSnapshot snapshot;
    private String selectedSession = "main";
    private GuidePersistenceSnapshot persistence;
    private boolean allowHistoryWrites;
    private boolean historyDeletionPending;
    private boolean disconnected;
    private boolean incrementalHistory;
    private final DurableProjection durableProjection = new DurableProjection();
    private final List<GuideHistoryMutation> pendingHistoryMutations = new ArrayList<>();

    public GuideService(
            UUID actor,
            GuideLocalEndpoint local,
            GuideRemoteEndpoint remote,
            GuideContextProvider contexts,
            ClientEventDispatcher dispatcher,
            Clock clock,
            Gson gson) {
        this(actor, local, remote, contexts, dispatcher, clock, gson, null, null);
    }

    public GuideService(
            UUID actor,
            GuideLocalEndpoint local,
            GuideRemoteEndpoint remote,
            GuideContextProvider contexts,
            ClientEventDispatcher dispatcher,
            Clock clock,
            Gson gson,
            GuideHistoryScope historyScope,
            GuideHistoryAccess history) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.local = local;
        this.remote = Objects.requireNonNull(remote, "remote");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        if ((historyScope == null) != (history == null)) {
            throw new IllegalArgumentException("history scope and access must be configured together");
        }
        if (historyScope != null && !historyScope.actorId().equals(actor)) {
            throw new IllegalArgumentException("history scope belongs to another actor");
        }
        this.historyScope = historyScope;
        this.history = history;
        reducer = new GuideStateReducer(gson);
        sessions.put("main", new SessionState("main", defaultClientSelection()));
        persistence = history == null
                ? GuidePersistenceSnapshot.disabled()
                : GuidePersistenceSnapshot.loading();
        snapshot = buildSnapshot();
        if (history != null) {
            startHistoryLoad();
        }
    }

    public GuideSnapshot snapshot() {
        return snapshot;
    }

    public GuideSubscription subscribe(Consumer<GuideSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        dispatcher.execute(() -> {
            listeners.add(listener);
            listener.accept(snapshot);
        });
        return () -> listeners.remove(listener);
    }

    public CompletableFuture<ToolResult<UUID>> ask(String question) {
        CompletableFuture<ToolResult<UUID>> result = new CompletableFuture<>();
        dispatcher.execute(() -> submit(selectedSession, question, result));
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> cancel() {
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            if (rejectStateChange(result)) {
                return;
            }
            SessionState session = sessions.get(selectedSession);
            GuideRequestSnapshot active = active(session);
            if (active == null) {
                result.complete(new ToolResult.Success<>(false));
                return;
            }
            boolean preparingContext = session.preparingContextRequest != null
                    && session.preparingContextRequest.equals(active.requestId());
            boolean cancelled;
            if (preparingContext) {
                session.contextGeneration++;
                session.preparingContextRequest = null;
                cancelled = true;
            } else {
                cancelled = active.topology() == GuideTopology.SERVER
                        ? remote.cancel(active.requestId())
                        : local != null && local.cancel(actor, active.sessionId());
            }
            if (cancelled) {
                apply(active.requestId(), new AgentEvent.Failed(
                        "agent_cancelled", "Agent request was cancelled"));
            }
            result.complete(new ToolResult.Success<>(cancelled));
        });
        return result;
    }

    public CompletableFuture<ToolResult<UUID>> retry(UUID requestId) {
        CompletableFuture<ToolResult<UUID>> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            if (rejectStateChange(result)) {
                return;
            }
            GuideRequestSnapshot request = find(requestId);
            if (request == null || !request.terminal()
                    || (request.status() != GuideRequestStatus.FAILED
                            && request.status() != GuideRequestStatus.CANCELLED
                            && request.status() != GuideRequestStatus.INTERRUPTED)) {
                result.complete(new ToolResult.Failure<>(
                        "retry_unavailable", "Only a failed or cancelled request can be retried"));
                return;
            }
            submit(request.sessionId(), request.userMessage(), result);
        });
        return result;
    }

    public CompletableFuture<ToolResult<String>> selectSession(String sessionId) {
        CompletableFuture<ToolResult<String>> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            if (rejectStateChange(result)) {
                return;
            }
            if (!validSession(sessionId)) {
                result.complete(new ToolResult.Failure<>(
                        "invalid_session", "Session ID may contain letters, numbers, dot, underscore, and dash"));
                return;
            }
            sessions.computeIfAbsent(sessionId,
                    id -> new SessionState(id, defaultClientSelection()));
            selectedSession = sessionId;
            publish();
            result.complete(new ToolResult.Success<>(sessionId));
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> closeSession(String sessionId) {
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            if (rejectStateChange(result)) {
                return;
            }
            SessionState session = sessions.get(sessionId);
            if (session == null) {
                result.complete(new ToolResult.Success<>(false));
                return;
            }
            GuideRequestSnapshot active = active(session);
            if (active != null) {
                if (active.topology() == GuideTopology.SERVER) {
                    remote.cancel(active.requestId());
                } else if (local != null) {
                    local.cancel(actor, sessionId);
                }
            }
            if (local != null) {
                local.clearSession(actor, sessionId);
            }
            invalidatePageLoad(session, "history_page_cancelled", "History page request was cancelled");
            session.requests.forEach(request -> requestSessions.remove(request.requestId()));
            durableProjection.removeSession(sessionId, session.requests);
            pendingHistoryMutations.add(new GuideHistoryMutation.DeleteSession(sessionId));
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                sessions.put("main", new SessionState("main", defaultClientSelection()));
            }
            if (selectedSession.equals(sessionId)) {
                selectedSession = sessions.containsKey("main")
                        ? "main"
                        : sessions.keySet().iterator().next();
            }
            publish();
            result.complete(new ToolResult.Success<>(true));
        });
        return result;
    }

    /** Captures the selected session without exposing a history path or mutating its viewport. */
    public CompletableFuture<ToolResult<GuideSessionExportSnapshot>>
            captureSelectedSessionForExport() {
        CompletableFuture<ToolResult<GuideSessionExportSnapshot>> result =
                new CompletableFuture<>();
        dispatcher.execute(() -> {
            if (disconnected) {
                result.complete(new ToolResult.Failure<>(
                        "history_export_unavailable", "Guide session export is unavailable"));
                return;
            }
            SessionState session = sessions.get(selectedSession);
            if (session == null) {
                result.complete(new ToolResult.Failure<>(
                        "invalid_session", "Guide session does not exist"));
                return;
            }
            try {
                String capturedSession = session.id;
                Instant capturedAt = clock.instant();
                List<GuideSessionExportCollector.SequencedRequest> captured =
                        session.requests.stream().map(request ->
                                new GuideSessionExportCollector.SequencedRequest(
                                        Objects.requireNonNull(
                                                session.requestSequences.get(request.requestId()),
                                                "request sequence"),
                                        request)).toList();
                GuideSessionExportCollector collector =
                        new GuideSessionExportCollector(historyScope, history);
                collector.collect(capturedSession, captured, capturedAt)
                        .whenComplete((export, failure) -> dispatcher.execute(() -> {
                            if (failure == null) {
                                result.complete(new ToolResult.Success<>(export));
                            } else {
                                result.complete(new ToolResult.Failure<>(
                                        "history_export_failed",
                                        "Unable to read the complete guide session for export"));
                            }
                        }));
            } catch (RuntimeException failure) {
                result.complete(new ToolResult.Failure<>(
                        "history_export_failed",
                        "Unable to read the complete guide session for export"));
            }
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> clearSelectedSession() {
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            if (rejectStateChange(result)) {
                return;
            }
            SessionState session = sessions.get(selectedSession);
            if (active(session) != null) {
                result.complete(new ToolResult.Failure<>(
                        "agent_busy", "Cannot clear a session while its request is active"));
                return;
            }
            invalidatePageLoad(session, "history_page_cancelled", "History page request was cancelled");
            session.requests.forEach(request -> requestSessions.remove(request.requestId()));
            durableProjection.removeRequests(session.requests);
            session.requests.clear();
            session.messages.clear();
            session.requestSequences.clear();
            session.totalRequests = 0;
            session.nextRequestSequence = 0;
            session.firstAvailable = null;
            session.lastAvailable = null;
            session.firstLoaded = null;
            session.lastLoaded = null;
            session.hasEarlier = false;
            session.hasLater = false;
            pendingHistoryMutations.add(new GuideHistoryMutation.ClearSession(selectedSession));
            if (local != null) {
                local.clearSession(actor, selectedSession);
            }
            publish();
            result.complete(new ToolResult.Success<>(true));
        });
        return result;
    }

    public CompletableFuture<ToolResult<GuideModelMode>> setModelMode(GuideModelMode mode) {
        CompletableFuture<ToolResult<GuideModelMode>> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            if (rejectStateChange(result)) {
                return;
            }
            Objects.requireNonNull(mode, "mode");
            SessionState session = sessions.get(selectedSession);
            GuideModelSelection selection = mode == GuideModelMode.SERVER
                    ? GuideModelSelection.server()
                    : GuideModelSelection.client(session.lastClientProfileId);
            GuideFailure failure = selectionFailure(selection);
            if (failure != null) {
                result.complete(new ToolResult.Failure<>(failure.code(), failure.message()));
                return;
            }
            select(session, selection);
            publish();
            result.complete(new ToolResult.Success<>(mode));
        });
        return result;
    }

    public CompletableFuture<ToolResult<GuideModelSelection>> setModelSelection(
            GuideModelSelection selection) {
        CompletableFuture<ToolResult<GuideModelSelection>> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            if (rejectStateChange(result)) {
                return;
            }
            Objects.requireNonNull(selection, "selection");
            GuideFailure failure = selectionFailure(selection);
            if (failure != null) {
                result.complete(new ToolResult.Failure<>(failure.code(), failure.message()));
                return;
            }
            select(sessions.get(selectedSession), selection);
            publish();
            result.complete(new ToolResult.Success<>(selection));
        });
        return result;
    }

    public CompletableFuture<Void> refreshCapabilities() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            if (!remote.serverModelAvailable()) {
                List<UUID> affected = sessions.values().stream()
                        .map(GuideService::active)
                        .filter(Objects::nonNull)
                        .filter(request -> request.topology() == GuideTopology.SERVER)
                        .map(GuideRequestSnapshot::requestId)
                        .toList();
                affected.forEach(requestId -> apply(requestId, new AgentEvent.Failed(
                        "capability_unavailable",
                        "The active server model capability disappeared")));
            }
            publishWithoutSave();
            result.complete(null);
        });
        return result;
    }

    public CompletableFuture<ToolResult<GuideHistoryPage>> requestHistoryWindow(
            String sessionId,
            GuideHistoryPageRequest.Direction direction,
            GuideHistoryCursor cursor,
            int count) {
        CompletableFuture<ToolResult<GuideHistoryPage>> result = new CompletableFuture<>();
        dispatcher.execute(() -> startHistoryWindow(
                sessionId, direction, cursor, count, result));
        return result;
    }

    public CompletableFuture<Void> disconnect() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            List<GuideRequestSnapshot> activeRequests = sessions.values().stream()
                    .map(GuideService::active)
                    .filter(Objects::nonNull)
                    .toList();
            for (GuideRequestSnapshot active : activeRequests) {
                if (active.topology() == GuideTopology.SERVER) {
                    remote.cancel(active.requestId());
                } else if (local != null) {
                    local.cancel(actor, active.sessionId());
                }
                apply(active.requestId(), new AgentEvent.Failed(
                        "agent_cancelled", "Agent request was cancelled by disconnect"));
            }
            CompletableFuture<Void> durable = history != null && allowHistoryWrites
                    ? history.flush()
                    : CompletableFuture.completedFuture(null);
            sessions.values().forEach(session -> invalidatePageLoad(
                    session, "history_page_cancelled", "History page request was cancelled by disconnect"));
            disconnected = true;
            if (local != null) {
                local.clearActor(actor);
            }
            remote.disconnect();
            requestSessions.clear();
            sessions.clear();
            sessions.put("main", new SessionState("main", defaultClientSelection()));
            selectedSession = "main";
            publishWithoutSave();
            durable.handle((ignored, failure) -> null).thenRun(() -> result.complete(null));
        });
        return result;
    }

    public CompletableFuture<Void> shutdown() {
        return disconnect();
    }

    @Override
    public CompletableFuture<ToolResult<Boolean>> deleteCurrentHistory() {
        return administerHistory(HistoryAdministrationKind.PARTITION);
    }

    @Override
    public CompletableFuture<ToolResult<Boolean>> deleteActorHistory() {
        return administerHistory(HistoryAdministrationKind.ACTOR);
    }

    CompletableFuture<ToolResult<Boolean>> resetHistoryDatabase() {
        return administerHistory(HistoryAdministrationKind.DATABASE);
    }

    private CompletableFuture<ToolResult<Boolean>> administerHistory(
            HistoryAdministrationKind kind) {
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        dispatcher.execute(() -> startHistoryAdministration(kind, result));
        return result;
    }

    private void startHistoryAdministration(
            HistoryAdministrationKind kind,
            CompletableFuture<ToolResult<Boolean>> result) {
        GuideFailure unavailable;
        try {
            unavailable = historyAdministrationFailure(kind);
        } catch (RuntimeException failure) {
            GuideFailure historyFailure = historyFailure(failure, "history_delete_failed");
            result.complete(new ToolResult.Failure<>(
                    historyFailure.code(), historyFailure.message()));
            return;
        }
        if (unavailable != null) {
            result.complete(new ToolResult.Failure<>(
                    unavailable.code(), unavailable.message()));
            return;
        }
        historyDeletionPending = true;
        CompletableFuture<Void> deletion;
        try {
            deletion = switch (kind) {
                case PARTITION -> history.delete(
                        GuideHistoryDeleteScope.partition(historyScope));
                case ACTOR -> history.delete(GuideHistoryDeleteScope.actor(actor));
                case DATABASE -> history.resetDatabase();
            };
            Objects.requireNonNull(deletion, "history deletion future");
        } catch (RuntimeException failure) {
            finishHistoryAdministration(failure, result);
            return;
        }
        deletion.whenComplete((ignored, failure) -> dispatcher.execute(
                () -> finishHistoryAdministration(failure, result)));
    }

    private GuideFailure historyAdministrationFailure(HistoryAdministrationKind kind) {
        if (history == null || historyScope == null || disconnected) {
            return new GuideFailure(
                    "history_unavailable", "Durable guide history is unavailable");
        }
        if (historyDeletionPending
                || persistence.state() == GuidePersistenceSnapshot.State.SAVING
                || !history.activity().idleForDeletion()
                || sessions.values().stream().anyMatch(session -> active(session) != null)) {
            return new GuideFailure("history_delete_busy", "Guide history is busy");
        }
        if (persistence.state() == GuidePersistenceSnapshot.State.LOADING) {
            return new GuideFailure(
                    "history_loading", "Durable guide history is still loading");
        }
        if (kind != HistoryAdministrationKind.DATABASE
                && (!allowHistoryWrites
                        || persistence.state() == GuidePersistenceSnapshot.State.UNAVAILABLE)) {
            return new GuideFailure(
                    "history_unavailable", "Durable guide history is unavailable");
        }
        return null;
    }

    private void finishHistoryAdministration(
            Throwable failure,
            CompletableFuture<ToolResult<Boolean>> result) {
        if (failure != null) {
            historyDeletionPending = false;
            GuideFailure historyFailure = historyFailure(failure, "history_delete_failed");
            result.complete(new ToolResult.Failure<>(
                    historyFailure.code(), historyFailure.message()));
            return;
        }
        if (!disconnected) {
            resetHistoryMemory();
            allowHistoryWrites = true;
            persistence = GuidePersistenceSnapshot.available(0);
            historyDeletionPending = false;
            publishWithoutSave();
        } else {
            historyDeletionPending = false;
        }
        result.complete(new ToolResult.Success<>(Boolean.TRUE));
    }

    private void resetHistoryMemory() {
        if (local != null) {
            try {
                local.clearActor(actor);
            } catch (RuntimeException ignored) {
                // Durable deletion is already committed; stale model memory cannot block reset.
            }
        }
        requestSessions.clear();
        sessions.values().forEach(session -> invalidatePageLoad(
                session, "history_page_cancelled", "History page request was cancelled"));
        sessions.clear();
        durableProjection.clear();
        pendingHistoryMutations.clear();
        sessions.put("main", new SessionState("main", defaultClientSelection()));
        selectedSession = "main";
    }

    private static void invalidatePageLoad(SessionState session, String code, String message) {
        session.windowGeneration++;
        PageLoad load = session.pageLoad;
        session.pageLoad = null;
        session.pageState = GuideHistoryPageState.IDLE;
        session.pageFailure = null;
        if (load != null) {
            load.waiters().forEach(waiter -> waiter.complete(new ToolResult.Failure<>(code, message)));
        }
    }

    private void submit(
            String sessionId, String question, CompletableFuture<ToolResult<UUID>> result) {
        if (rejectStateChange(result)) {
            return;
        }
        if (question == null || question.isBlank()) {
            result.complete(new ToolResult.Failure<>(
                    "invalid_arguments", "Question must not be blank"));
            return;
        }
        SessionState session = sessions.computeIfAbsent(
                sessionId, id -> new SessionState(id, defaultClientSelection()));
        if (active(session) != null) {
            result.complete(new ToolResult.Failure<>(
                    "agent_busy", "This guide session already has an active request"));
            return;
        }
        GuideModelSelection capturedSelection = session.modelSelection;
        GuideTopology topology;
        if (capturedSelection.kind() == GuideModelSelection.Kind.SERVER) {
            if (!remote.serverModelAvailable()) {
                result.complete(new ToolResult.Failure<>(
                        "capability_unavailable", "The connected server does not provide a model"));
                return;
            }
            topology = GuideTopology.SERVER;
        } else {
            GuideFailure failure = selectionFailure(capturedSelection);
            if (failure != null) {
                result.complete(new ToolResult.Failure<>(failure.code(), failure.message()));
                return;
            }
            topology = remote.serverToolsAvailable()
                    ? GuideTopology.CLIENT_WITH_SERVER_TOOLS
                    : GuideTopology.CLIENT_LOCAL;
        }

        UUID requestId = UUID.randomUUID();
        Instant now = clock.instant();
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                requestId, sessionId, topology, question, now, capturedSelection);
        session.requests.add(request);
        long requestSequence = session.nextRequestSequence++;
        session.requestSequences.put(requestId, requestSequence);
        session.totalRequests++;
        GuideHistoryCursor requestCursor = new GuideHistoryCursor(requestSequence, requestId);
        if (session.firstAvailable == null) session.firstAvailable = requestCursor;
        session.lastAvailable = requestCursor;
        if (session.firstLoaded == null) session.firstLoaded = requestCursor;
        session.lastLoaded = requestCursor;
        session.hasLater = false;
        session.messages.add(new GuideMessage(
                requestId, GuideMessage.Role.USER, question, now));
        requestSessions.put(requestId, sessionId);
        publish();

        if (topology == GuideTopology.SERVER) {
            if (incrementalHistory) {
                prepareRemoteContext(session, requestId, question);
            } else {
                List<GuideMessage> previousHistory = List.copyOf(
                        session.messages.subList(0, session.messages.size() - 1));
                if (!remote.ask(
                        requestId,
                        sessionId,
                        question,
                        previousHistory,
                        event -> apply(requestId, event))) {
                    apply(requestId, new AgentEvent.Failed(
                            "capability_unavailable",
                            "The connected server rejected the model request"));
                    result.complete(new ToolResult.Failure<>(
                            "capability_unavailable",
                            "The connected server rejected the model request"));
                    return;
                }
            }
        } else if (incrementalHistory) {
            prepareLocalContext(
                    session, requestId, capturedSelection.profileId(), question);
        } else {
            dispatchLocal(requestId, sessionId, capturedSelection.profileId(), question);
        }
        result.complete(new ToolResult.Success<>(requestId));
    }

    private void prepareLocalContext(
            SessionState session,
            UUID requestId,
            String profileId,
            String question) {
        GuideContextSpec spec = local.contextSpec(profileId).orElse(null);
        if (spec == null) {
            apply(requestId, new AgentEvent.Failed(
                    "context_budget_unavailable",
                    "The selected model does not provide a valid context budget"));
            return;
        }
        int index = indexOf(session, requestId);
        if (index < 0) return;
        session.requests.set(index, withStatus(
                session.requests.get(index), GuideRequestStatus.CONTEXT_LOADING, clock.instant()));
        long generation = ++session.contextGeneration;
        session.preparingContextRequest = requestId;
        publish();
        CompletableFuture<GuideHistoryContextSeed> loading;
        try {
            loading = Objects.requireNonNull(history.context(new GuideHistoryContextRequest(
                    historyScope,
                    session.id,
                    spec.budget(),
                    spec.promptAndToolTokens(),
                    spec.canonicalModelId())), "history context future");
        } catch (RuntimeException failure) {
            finishLocalContext(
                    session.id, requestId, profileId, question, generation, null, failure);
            return;
        }
        loading.whenComplete((seed, failure) -> dispatcher.execute(() -> finishLocalContext(
                session.id, requestId, profileId, question, generation, seed, failure)));
    }

    private void prepareRemoteContext(
            SessionState session, UUID requestId, String question) {
        GuideContextSpec spec = remote.contextSpec().orElse(null);
        if (spec == null) {
            apply(requestId, new AgentEvent.Failed(
                    "context_budget_unavailable",
                    "The server model does not provide a valid context budget"));
            return;
        }
        int index = indexOf(session, requestId);
        if (index < 0) return;
        session.requests.set(index, withStatus(
                session.requests.get(index), GuideRequestStatus.CONTEXT_LOADING, clock.instant()));
        long generation = ++session.contextGeneration;
        session.preparingContextRequest = requestId;
        publish();
        CompletableFuture<GuideHistoryContextSeed> loading;
        try {
            loading = Objects.requireNonNull(history.context(new GuideHistoryContextRequest(
                    historyScope,
                    session.id,
                    spec.budget(),
                    spec.promptAndToolTokens(),
                    spec.canonicalModelId())), "history context future");
        } catch (RuntimeException failure) {
            finishRemoteContext(
                    session.id, requestId, question, generation, null, failure);
            return;
        }
        loading.whenComplete((seed, failure) -> dispatcher.execute(() -> finishRemoteContext(
                session.id, requestId, question, generation, seed, failure)));
    }

    private void finishRemoteContext(
            String sessionId,
            UUID requestId,
            String question,
            long generation,
            GuideHistoryContextSeed seed,
            Throwable failure) {
        if (disconnected) return;
        SessionState session = sessions.get(sessionId);
        GuideRequestSnapshot request = find(requestId);
        if (session == null || request == null || request.terminal()
                || session.contextGeneration != generation
                || !requestId.equals(session.preparingContextRequest)) {
            return;
        }
        session.preparingContextRequest = null;
        if (failure != null || seed == null || !sessionId.equals(seed.sessionId())) {
            apply(requestId, new AgentEvent.Failed(
                    "history_context_failed",
                    "Unable to prepare durable guide context"));
            return;
        }
        boolean accepted;
        try {
            accepted = remote.askWithContext(
                    requestId, sessionId, question, seed.messages(),
                    event -> apply(requestId, event));
        } catch (RuntimeException malformed) {
            accepted = false;
        }
        if (!accepted) {
            apply(requestId, new AgentEvent.Failed(
                    "capability_unavailable",
                    "The connected server rejected the model request"));
        }
    }

    private void finishLocalContext(
            String sessionId,
            UUID requestId,
            String profileId,
            String question,
            long generation,
            GuideHistoryContextSeed seed,
            Throwable failure) {
        if (disconnected) return;
        SessionState session = sessions.get(sessionId);
        if (session == null
                || session.contextGeneration != generation
                || !requestId.equals(session.preparingContextRequest)
                || find(requestId) == null
                || find(requestId).terminal()) {
            return;
        }
        session.preparingContextRequest = null;
        if (failure != null || seed == null || !sessionId.equals(seed.sessionId())) {
            apply(requestId, new AgentEvent.Failed(
                    "history_context_failed",
                    "Unable to prepare durable guide context"));
            return;
        }
        try {
            local.hydrateContext(actor, sessionId, seed.messages(), seed.checkpoints());
        } catch (RuntimeException malformed) {
            apply(requestId, new AgentEvent.Failed(
                    "history_context_failed",
                    "Unable to prepare durable guide context"));
            return;
        }
        dispatchLocal(requestId, sessionId, profileId, question);
    }

    private void dispatchLocal(
            UUID requestId, String sessionId, String profileId, String question) {
        GuideRequestSnapshot request = find(requestId);
        if (request == null || request.terminal()) return;
        Set<dev.openallay.context.ContextCapability> requiredContext;
        try {
            requiredContext = local.requiredContext(profileId);
        } catch (GuideModelProfileException failure) {
            apply(requestId, new AgentEvent.Failed(failure.code(), failure.getMessage()));
            return;
        }
        ToolResult<ToolInvocationContext> captured =
                contexts.capture(requiredContext, requestId.toString());
        if (captured instanceof ToolResult.Failure<ToolInvocationContext> failure) {
            apply(requestId, new AgentEvent.Failed(failure.code(), failure.message()));
            return;
        }
        ToolInvocationContext context =
                ((ToolResult.Success<ToolInvocationContext>) captured).value();
        try {
            local.ask(
                            profileId,
                            actor,
                            sessionId,
                            requestId,
                            question,
                            context,
                            event -> apply(requestId, event))
                    .whenComplete((ignored, throwable) -> {
                        if (throwable != null) {
                            dispatcher.execute(() -> failIfActive(requestId, throwable));
                        }
                    });
        } catch (RuntimeException failure) {
            apply(requestId, new AgentEvent.Failed(
                    "agent_failure", message(failure)));
        }
    }

    private static GuideRequestSnapshot withStatus(
            GuideRequestSnapshot request, GuideRequestStatus status, Instant now) {
        GuideRequestProgress progress = request.progress().advance(
                status == GuideRequestStatus.CONTEXT_LOADING
                        ? GuideRequestPhase.CONTEXT_LOADING
                        : request.progress().phase(),
                now);
        return new GuideRequestSnapshot(
                request.requestId(), request.sessionId(), request.topology(), request.userMessage(),
                request.timeline(), status, request.sources(), request.usage(),
                request.retryAfterMillis(), request.failure(), request.createdAt(),
                progress.lastProgressAt(), request.terminalAt(), request.modelSelection(), progress);
    }

    private void apply(UUID requestId, AgentEvent event) {
        String sessionId = requestSessions.get(requestId);
        if (sessionId == null) {
            return;
        }
        SessionState session = sessions.get(sessionId);
        int index = indexOf(session, requestId);
        if (index < 0) {
            return;
        }
        if (event instanceof AgentEvent.ContextCompacted compacted) {
            session.checkpoints.add(compacted.checkpoint());
            publish();
            return;
        }
        GuideRequestSnapshot before = session.requests.get(index);
        GuideRequestSnapshot after = reducer.apply(before, event, clock.instant());
        if (before == after) {
            return;
        }
        session.requests.set(index, after);
        if (after.status() == GuideRequestStatus.COMPLETED
                && !after.assistantText().isBlank()) {
            session.messages.add(new GuideMessage(
                    after.requestId(),
                    GuideMessage.Role.ASSISTANT,
                    after.assistantText(),
                    after.terminalAt()));
        }
        publish();
    }

    private void failIfActive(UUID requestId, Throwable throwable) {
        GuideRequestSnapshot request = find(requestId);
        if (request != null && !request.terminal()) {
            Throwable failure = unwrap(throwable);
            if (failure instanceof GuideModelProfileException profileFailure) {
                apply(requestId, new AgentEvent.Failed(
                        profileFailure.code(), profileFailure.getMessage()));
            } else {
                apply(requestId, new AgentEvent.Failed("agent_failure", message(failure)));
            }
        }
    }

    private GuideRequestSnapshot find(UUID requestId) {
        String sessionId = requestSessions.get(requestId);
        if (sessionId == null) {
            return null;
        }
        SessionState session = sessions.get(sessionId);
        int index = indexOf(session, requestId);
        return index < 0 ? null : session.requests.get(index);
    }

    private static int indexOf(SessionState session, UUID requestId) {
        if (session == null) {
            return -1;
        }
        for (int index = 0; index < session.requests.size(); index++) {
            if (session.requests.get(index).requestId().equals(requestId)) {
                return index;
            }
        }
        return -1;
    }

    private static GuideRequestSnapshot active(SessionState session) {
        if (session == null || session.requests.isEmpty()) {
            return null;
        }
        GuideRequestSnapshot latest = session.requests.getLast();
        return latest.terminal() ? null : latest;
    }

    private void publish() {
        scheduleHistorySave();
        publishWithoutSave();
    }

    private void publishWithoutSave() {
        snapshot = buildSnapshot();
        for (Consumer<GuideSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // One screen/command observer cannot break product state delivery.
            }
        }
    }

    private GuideSnapshot buildSnapshot() {
        List<GuideSessionSnapshot> copies = sessions.values().stream()
                .map(session -> new GuideSessionSnapshot(
                        session.id,
                        session.messages,
                        session.requests,
                        session.checkpoints,
                        session.modelSelection,
                        historyWindow(session)))
                .toList();
        GuideModelSelection currentSelection = sessions.get(selectedSession).modelSelection;
        List<GuideClientModelProfile> profiles = local == null ? List.of() : local.profiles();
        return new GuideSnapshot(
                actor,
                selectedSession,
                currentSelection.modelMode(),
                profiles.stream().anyMatch(GuideClientModelProfile::available),
                remote.serverModelAvailable(),
                persistence,
                copies,
                clock.instant(),
                currentSelection,
                profiles);
    }

    private void startHistoryLoad() {
        CompletableFuture<java.util.Optional<GuideHistoryMetadata>> loading;
        try {
            loading = Objects.requireNonNull(
                    history.metadata(historyScope), "history metadata future");
        } catch (RuntimeException failure) {
            completeHistoryLoad(null, failure);
            return;
        }
        loading.whenComplete((loaded, failure) ->
                dispatcher.execute(() -> completeHistoryLoad(loaded, failure)));
    }

    private void completeHistoryLoad(
            java.util.Optional<GuideHistoryMetadata> loaded, Throwable failure) {
        if (disconnected) {
            return;
        }
        if (failure != null && historyFailure(failure, "history_load_failed")
                .code().equals("history_operation_unsupported")) {
            startLegacyHistoryLoad();
            return;
        }
        if (failure != null) {
            allowHistoryWrites = false;
            persistence = unavailable(failure, "history_load_failed");
            publishWithoutSave();
            return;
        }
        if (loaded == null) {
            allowHistoryWrites = false;
            persistence = unavailable(
                    new GuideHistoryException("history_load_failed", "History load returned no result"),
                    "history_load_failed");
            publishWithoutSave();
            return;
        }
        if (loaded.isPresent()) {
            GuideHistoryMetadata metadata = loaded.orElseThrow();
            if (!metadata.scope().equals(historyScope)) {
                allowHistoryWrites = false;
                persistence = new GuidePersistenceSnapshot(
                        GuidePersistenceSnapshot.State.UNAVAILABLE,
                        0,
                        0,
                        new GuideFailure(
                                "history_scope_mismatch",
                                "Loaded history belongs to another partition"));
                publishWithoutSave();
                return;
            }
            hydrateMetadata(metadata);
        }
        allowHistoryWrites = true;
        incrementalHistory = true;
        persistence = GuidePersistenceSnapshot.available(0);
        publishWithoutSave();
    }

    private void startLegacyHistoryLoad() {
        CompletableFuture<GuideHistoryLoad> loading;
        try {
            loading = Objects.requireNonNull(history.load(historyScope), "history load future");
        } catch (RuntimeException failure) {
            completeLegacyHistoryLoad(null, failure);
            return;
        }
        loading.whenComplete((loaded, failure) -> dispatcher.execute(
                () -> completeLegacyHistoryLoad(loaded, failure)));
    }

    private void completeLegacyHistoryLoad(GuideHistoryLoad loaded, Throwable failure) {
        if (disconnected) return;
        if (failure != null || loaded == null) {
            allowHistoryWrites = false;
            persistence = unavailable(
                    failure == null
                            ? new GuideHistoryException(
                                    "history_load_failed", "History load returned no result")
                            : failure,
                    "history_load_failed");
            publishWithoutSave();
            return;
        }
        if (!loaded.diagnostics().isEmpty()) {
            allowHistoryWrites = false;
            persistence = new GuidePersistenceSnapshot(
                    GuidePersistenceSnapshot.State.UNAVAILABLE,
                    0,
                    0,
                    loaded.diagnostics().getFirst());
            publishWithoutSave();
            return;
        }
        loaded.partition().ifPresent(this::hydrate);
        allowHistoryWrites = true;
        incrementalHistory = false;
        persistence = GuidePersistenceSnapshot.available(0);
        publishWithoutSave();
    }

    private void hydrateMetadata(GuideHistoryMetadata metadata) {
        sessions.clear();
        requestSessions.clear();
        for (GuideHistoryMetadata.Session snapshot : metadata.sessions()) {
            SessionState session = new SessionState(snapshot.sessionId(), snapshot.modelSelection());
            session.totalRequests = snapshot.requestCount();
            session.firstAvailable = snapshot.first();
            session.lastAvailable = snapshot.last();
            session.hasEarlier = snapshot.requestCount() > 0;
            session.hasLater = false;
            session.nextRequestSequence = snapshot.last() == null
                    ? 0 : snapshot.last().sequence() + 1;
            sessions.put(session.id, session);
            durableProjection.sessions.put(
                    session.id,
                    new SessionProjection(snapshot.ordinal(), snapshot.modelSelection()));
        }
        selectedSession = metadata.selectedSession();
        durableProjection.selectedSession = selectedSession;
    }

    private void startHistoryWindow(
            String sessionId,
            GuideHistoryPageRequest.Direction direction,
            GuideHistoryCursor cursor,
            int count,
            CompletableFuture<ToolResult<GuideHistoryPage>> result) {
        if (history == null || !allowHistoryWrites || disconnected) {
            result.complete(new ToolResult.Failure<>(
                    "history_unavailable", "Durable guide history is unavailable"));
            return;
        }
        SessionState session = sessions.get(sessionId);
        if (session == null) {
            result.complete(new ToolResult.Failure<>(
                    "invalid_session", "Guide session does not exist"));
            return;
        }
        GuideHistoryPageRequest request;
        try {
            request = new GuideHistoryPageRequest(
                    historyScope, sessionId, direction, cursor, count);
        } catch (IllegalArgumentException failure) {
            result.complete(new ToolResult.Failure<>("invalid_arguments", failure.getMessage()));
            return;
        }
        PageKey key = new PageKey(direction, cursor, count);
        if (session.pageLoad != null && session.pageLoad.key().equals(key)) {
            session.pageLoad.waiters().add(result);
            return;
        }
        if (session.pageLoad != null) {
            session.pageLoad.waiters().forEach(waiter -> waiter.complete(
                    new ToolResult.Failure<>(
                            "history_page_superseded", "A newer history page was requested")));
        }
        long generation = ++session.windowGeneration;
        PageLoad load = new PageLoad(key, generation, new ArrayList<>(List.of(result)));
        session.pageLoad = load;
        session.pageState = GuideHistoryPageState.LOADING;
        session.pageFailure = null;
        publishWithoutSave();
        CompletableFuture<GuideHistoryPage> loading;
        try {
            loading = Objects.requireNonNull(history.page(request), "history page future");
        } catch (RuntimeException failure) {
            finishHistoryWindow(sessionId, load, null, failure);
            return;
        }
        loading.whenComplete((page, failure) -> dispatcher.execute(
                () -> finishHistoryWindow(sessionId, load, page, failure)));
    }

    private void finishHistoryWindow(
            String sessionId,
            PageLoad load,
            GuideHistoryPage page,
            Throwable failure) {
        if (disconnected) return;
        SessionState session = sessions.get(sessionId);
        if (session == null || session.pageLoad != load
                || session.windowGeneration != load.generation()) {
            return;
        }
        session.pageLoad = null;
        if (failure != null || page == null) {
            GuideFailure pageFailure = historyFailure(
                    failure == null
                            ? new GuideHistoryException(
                                    "history_page_failed", "History page returned no result")
                            : failure,
                    "history_page_failed");
            session.pageState = GuideHistoryPageState.FAILED;
            session.pageFailure = pageFailure;
            load.waiters().forEach(waiter -> waiter.complete(
                    new ToolResult.Failure<>(pageFailure.code(), pageFailure.message())));
            publishWithoutSave();
            return;
        }
        mergePage(session, load.key().direction(), load.key().count(), page);
        session.pageState = GuideHistoryPageState.IDLE;
        session.pageFailure = null;
        load.waiters().forEach(waiter -> waiter.complete(new ToolResult.Success<>(page)));
        publishWithoutSave();
    }

    private void mergePage(
            SessionState session,
            GuideHistoryPageRequest.Direction direction,
            int neighborhoodCount,
            GuideHistoryPage page) {
        List<GuideRequestSnapshot> active = session.requests.stream()
                .filter(request -> !request.terminal()).toList();
        List<GuideRequestSnapshot> durable = session.requests.stream()
                .filter(GuideRequestSnapshot::terminal).collect(java.util.stream.Collectors.toCollection(
                        ArrayList::new));
        List<GuideRequestSnapshot> incoming = page.requests();
        if (direction == GuideHistoryPageRequest.Direction.NEWEST) {
            durable.clear();
            durable.addAll(incoming);
        } else if (direction == GuideHistoryPageRequest.Direction.BEFORE) {
            durable.addAll(0, incoming);
        } else {
            durable.addAll(incoming);
        }
        LinkedHashMap<UUID, GuideRequestSnapshot> previous = new LinkedHashMap<>();
        session.requests.forEach(request -> previous.put(request.requestId(), request));
        LinkedHashMap<UUID, GuideRequestSnapshot> unique = new LinkedHashMap<>();
        durable.forEach(request -> unique.put(request.requestId(), request));
        List<GuideRequestSnapshot> bounded = new ArrayList<>(unique.values());
        int maximumNeighborhood = Math.addExact(neighborhoodCount, page.requests().size());
        if (bounded.size() > maximumNeighborhood) {
            bounded = direction == GuideHistoryPageRequest.Direction.AFTER
                    ? new ArrayList<>(bounded.subList(
                            bounded.size() - maximumNeighborhood, bounded.size()))
                    : new ArrayList<>(bounded.subList(0, maximumNeighborhood));
        }
        unique.clear();
        bounded.forEach(request -> unique.put(request.requestId(), request));
        active.forEach(request -> unique.put(request.requestId(), request));
        session.requests.clear();
        session.requests.addAll(unique.values());
        previous.keySet().stream()
                .filter(requestId -> !unique.containsKey(requestId))
                .forEach(requestSessions::remove);
        page.requests().forEach(request -> requestSessions.put(request.requestId(), session.id));
        if (page.first() != null) {
            long sequence = page.first().sequence();
            for (GuideRequestSnapshot request : page.requests()) {
                session.requestSequences.put(request.requestId(), sequence++);
            }
        }
        List<GuideRequestSnapshot> loadedDurable = session.requests.stream()
                .filter(GuideRequestSnapshot::terminal).toList();
        session.firstLoaded = loadedDurable.isEmpty() ? null : cursor(
                session, loadedDurable.getFirst());
        session.lastLoaded = loadedDurable.isEmpty() ? null : cursor(
                session, loadedDurable.getLast());
        session.hasEarlier = page.hasEarlier();
        session.hasLater = page.hasLater();
        registerPageBaseline(page);
    }

    private static GuideHistoryCursor cursor(
            SessionState session, GuideRequestSnapshot request) {
        Long sequence = session.requestSequences.get(request.requestId());
        return sequence == null ? null : new GuideHistoryCursor(sequence, request.requestId());
    }

    private void registerPageBaseline(GuideHistoryPage page) {
        for (GuideRequestSnapshot original : page.requests()) {
            GuideRequestSnapshot request = durableRequest(original);
            durableProjection.requests.put(request.requestId(), rowProjection(request));
            for (GuideTimelineEntry entry : request.timeline()) {
                durableProjection.timeline.put(
                        new TimelineKey(request.requestId(), entry.ordinal()), entry);
            }
            durableProjection.sources.put(request.requestId(), List.copyOf(request.sources()));
        }
    }

    private GuideHistoryWindowSnapshot historyWindow(SessionState session) {
        if (history == null) {
            return GuideHistoryWindowSnapshot.disabled(session.requests.size());
        }
        return new GuideHistoryWindowSnapshot(
                session.totalRequests,
                session.firstAvailable,
                session.lastAvailable,
                session.firstLoaded,
                session.lastLoaded,
                session.hasEarlier,
                session.hasLater,
                session.pageState,
                session.windowGeneration,
                session.pageFailure);
    }

    private void hydrate(GuideHistoryPartition partition) {
        sessions.clear();
        requestSessions.clear();
        for (GuideSessionSnapshot snapshot : partition.sessions()) {
            GuideModelSelection restored = snapshot.modelSelection().kind()
                            == GuideModelSelection.Kind.SERVER
                    ? defaultClientSelection()
                    : snapshot.modelSelection();
            SessionState session = new SessionState(snapshot.sessionId(), restored);
            session.messages.addAll(snapshot.messages());
            session.requests.addAll(snapshot.requests());
            session.totalRequests = snapshot.requests().size();
            session.nextRequestSequence = snapshot.requests().size();
            for (int index = 0; index < snapshot.requests().size(); index++) {
                session.requestSequences.put(snapshot.requests().get(index).requestId(), (long) index);
            }
            session.checkpoints.addAll(snapshot.checkpoints());
            sessions.put(session.id, session);
            snapshot.requests().forEach(request ->
                    requestSessions.put(request.requestId(), session.id));
            if (local != null) {
                local.hydrateSession(
                        actor,
                        session.id,
                        snapshot.messages(),
                        snapshot.checkpoints());
            }
        }
        selectedSession = partition.selectedSession();
    }

    private void scheduleHistorySave() {
        if (history == null || !allowHistoryWrites || historyDeletionPending || disconnected) {
            return;
        }
        long generation = persistence.submittedGeneration() + 1;
        long committed = persistence.committedGeneration();
        persistence = new GuidePersistenceSnapshot(
                GuidePersistenceSnapshot.State.SAVING,
                generation,
                committed,
                null);
        try {
            CompletableFuture<Void> write;
            if (incrementalHistory) {
                GuideHistoryCommit commit = incrementalCommit();
                if (commit == null) {
                    persistence = GuidePersistenceSnapshot.available(committed);
                    return;
                }
                write = Objects.requireNonNull(history.commit(commit), "history commit future");
            } else {
                write = Objects.requireNonNull(
                        history.save(durablePartition()), "history save future");
            }
            write.whenComplete((ignored, failure) ->
                    dispatcher.execute(() -> finishHistorySave(generation, failure)));
        } catch (RuntimeException failure) {
            finishHistorySave(generation, failure);
        }
    }

    private GuideHistoryCommit incrementalCommit() {
        List<GuideHistoryMutation> mutations = new ArrayList<>(pendingHistoryMutations);
        pendingHistoryMutations.clear();
        int sessionOrdinal = 0;
        for (SessionState session : sessions.values()) {
            SessionProjection projected = new SessionProjection(
                    sessionOrdinal++, session.modelSelection);
            if (!projected.equals(durableProjection.sessions.get(session.id))) {
                mutations.add(new GuideHistoryMutation.UpsertSession(
                        session.id, projected.ordinal(), session.modelSelection));
                durableProjection.sessions.put(session.id, projected);
            }
            for (GuideRequestSnapshot original : session.requests) {
                Long sequence = session.requestSequences.get(original.requestId());
                if (sequence == null) continue;
                GuideRequestSnapshot request = durableRequest(original);
                GuideRequestSnapshot row = rowProjection(request);
                if (!row.equals(durableProjection.requests.get(request.requestId()))) {
                    mutations.add(new GuideHistoryMutation.UpsertRequest(sequence, row));
                    durableProjection.requests.put(request.requestId(), row);
                }
                for (GuideTimelineEntry entry : request.timeline()) {
                    TimelineKey key = new TimelineKey(request.requestId(), entry.ordinal());
                    if (!entry.equals(durableProjection.timeline.get(key))) {
                        mutations.add(new GuideHistoryMutation.UpsertTimelineEntry(
                                request.requestId(), entry));
                        durableProjection.timeline.put(key, entry);
                    }
                }
                if (!request.sources().equals(durableProjection.sources.get(request.requestId()))) {
                    mutations.add(new GuideHistoryMutation.ReplaceRequestSources(
                            request.requestId(), request.sources()));
                    durableProjection.sources.put(request.requestId(), List.copyOf(request.sources()));
                }
            }
            for (int ordinal = 0; ordinal < session.messages.size(); ordinal++) {
                MessageKey key = new MessageKey(session.id, ordinal);
                GuideMessage message = session.messages.get(ordinal);
                if (!message.equals(durableProjection.messages.get(key))) {
                    mutations.add(new GuideHistoryMutation.UpsertMessage(
                            session.id, ordinal, message));
                    durableProjection.messages.put(key, message);
                }
            }
            for (int ordinal = 0; ordinal < session.checkpoints.size(); ordinal++) {
                CheckpointKey key = new CheckpointKey(session.id, ordinal);
                ContextCheckpoint checkpoint = session.checkpoints.get(ordinal);
                if (!checkpoint.equals(durableProjection.checkpoints.get(key))) {
                    mutations.add(new GuideHistoryMutation.UpsertCheckpoint(
                            session.id, ordinal, checkpoint));
                    durableProjection.checkpoints.put(key, checkpoint);
                }
            }
        }
        if (mutations.isEmpty() && selectedSession.equals(durableProjection.selectedSession)) {
            return null;
        }
        mutations.add(0, new GuideHistoryMutation.UpsertPartition(
                selectedSession, clock.instant()));
        durableProjection.selectedSession = selectedSession;
        return new GuideHistoryCommit(historyScope, mutations);
    }

    private static GuideRequestSnapshot rowProjection(GuideRequestSnapshot request) {
        return new GuideRequestSnapshot(
                request.requestId(), request.sessionId(), request.topology(), request.userMessage(),
                List.of(), request.status(), List.of(), request.usage(),
                request.retryAfterMillis(), request.failure(), request.createdAt(),
                request.updatedAt(), request.terminalAt(), request.modelSelection());
    }

    private void finishHistorySave(long generation, Throwable failure) {
        if (disconnected || generation != persistence.submittedGeneration()) {
            return;
        }
        if (failure == null) {
            persistence = GuidePersistenceSnapshot.available(generation);
        } else {
            persistence = new GuidePersistenceSnapshot(
                    GuidePersistenceSnapshot.State.UNAVAILABLE,
                    generation,
                    persistence.committedGeneration(),
                    historyFailure(failure, "history_write_failed"));
        }
        publishWithoutSave();
    }

    private GuideHistoryPartition durablePartition() {
        List<GuideSessionSnapshot> durableSessions = sessions.values().stream()
                .map(session -> new GuideSessionSnapshot(
                        session.id,
                        session.messages,
                        session.requests.stream().map(GuideService::durableRequest).toList(),
                        session.checkpoints,
                        session.modelSelection))
                .toList();
        return new GuideHistoryPartition(
                GuideHistoryPartition.SCHEMA_VERSION,
                historyScope,
                selectedSession,
                durableSessions,
                clock.instant());
    }

    private static GuideRequestSnapshot durableRequest(GuideRequestSnapshot request) {
        List<GuideTimelineEntry> timeline = request.timeline().stream()
                .map(entry -> entry instanceof GuideTimelineEntry.Tool tool
                        ? new GuideTimelineEntry.Tool(
                                tool.ordinal(),
                                new GuideToolActivity(
                                        tool.activity().invocationId(),
                                        tool.activity().index(),
                                        tool.activity().toolId(),
                                        tool.activity().status(),
                                        null,
                                        tool.activity().uiReference(),
                                        tool.activity().diagnostics(),
                                        tool.activity().presentationMessages(),
                                        tool.activity().sources()))
                        : entry)
                .toList();
        return new GuideRequestSnapshot(
                request.requestId(),
                request.sessionId(),
                request.topology(),
                request.userMessage(),
                timeline,
                request.status(),
                request.sources(),
                request.usage(),
                request.retryAfterMillis(),
                request.failure(),
                request.createdAt(),
                request.updatedAt(),
                request.terminalAt(),
                request.modelSelection());
    }

    private <T> boolean rejectStateChange(CompletableFuture<ToolResult<T>> result) {
        if (historyDeletionPending) {
            result.complete(new ToolResult.Failure<>(
                    "history_delete_busy", "Guide history is busy"));
            return true;
        }
        if (persistence.state() != GuidePersistenceSnapshot.State.LOADING) {
            return false;
        }
        result.complete(new ToolResult.Failure<>(
                "history_loading", "Durable guide history is still loading"));
        return true;
    }

    private enum HistoryAdministrationKind {
        PARTITION,
        ACTOR,
        DATABASE
    }

    private static GuidePersistenceSnapshot unavailable(Throwable failure, String fallbackCode) {
        return new GuidePersistenceSnapshot(
                GuidePersistenceSnapshot.State.UNAVAILABLE,
                0,
                0,
                historyFailure(failure, fallbackCode));
    }

    private static GuideFailure historyFailure(Throwable failure, String fallbackCode) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof GuideHistoryException historyFailure) {
            return new GuideFailure(
                    historyFailure.code(),
                    historyFailure.getMessage() == null
                            ? "Durable guide history is unavailable"
                            : historyFailure.getMessage());
        }
        return new GuideFailure(fallbackCode, "Durable guide history is unavailable");
    }

    private GuideModelSelection defaultClientSelection() {
        return GuideModelSelection.client(local == null ? "default" : local.defaultProfileId());
    }

    private GuideFailure selectionFailure(GuideModelSelection selection) {
        if (selection.kind() == GuideModelSelection.Kind.SERVER) {
            return remote.serverModelAvailable()
                    ? null
                    : new GuideFailure(
                            "capability_unavailable",
                            "The connected server does not provide a model");
        }
        if (local == null) {
            return new GuideFailure(
                    "model_not_configured", "No usable client model is configured");
        }
        GuideClientModelProfile profile = local.profile(selection.profileId()).orElse(null);
        if (profile == null) {
            return new GuideFailure(
                    "model_not_configured", "The selected client model profile does not exist");
        }
        return profile.available() ? null : profile.failure();
    }

    private static void select(SessionState session, GuideModelSelection selection) {
        session.modelSelection = selection;
        if (selection.kind() == GuideModelSelection.Kind.CLIENT) {
            session.lastClientProfileId = selection.profileId();
        }
    }

    private static boolean validSession(String value) {
        return value != null && value.matches("[a-zA-Z0-9_.-]+");
    }

    GuideHistoryScope historyScope() {
        return historyScope;
    }

    private static String message(Throwable failure) {
        Throwable current = unwrap(failure);
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class SessionState {
        private final String id;
        private final List<GuideMessage> messages = new ArrayList<>();
        private final List<GuideRequestSnapshot> requests = new ArrayList<>();
        private final List<ContextCheckpoint> checkpoints = new ArrayList<>();
        private GuideModelSelection modelSelection;
        private String lastClientProfileId;
        private long totalRequests;
        private long nextRequestSequence;
        private final Map<UUID, Long> requestSequences = new LinkedHashMap<>();
        private GuideHistoryCursor firstAvailable;
        private GuideHistoryCursor lastAvailable;
        private GuideHistoryCursor firstLoaded;
        private GuideHistoryCursor lastLoaded;
        private boolean hasEarlier;
        private boolean hasLater;
        private GuideHistoryPageState pageState = GuideHistoryPageState.IDLE;
        private GuideFailure pageFailure;
        private long windowGeneration;
        private PageLoad pageLoad;
        private long contextGeneration;
        private UUID preparingContextRequest;

        private SessionState(String id, GuideModelSelection modelSelection) {
            if (!validSession(id)) {
                throw new IllegalArgumentException("invalid sessionId");
            }
            this.id = id;
            this.modelSelection = Objects.requireNonNull(modelSelection, "modelSelection");
            this.lastClientProfileId = modelSelection.kind() == GuideModelSelection.Kind.CLIENT
                    ? modelSelection.profileId() : "default";
        }
    }

    private record PageKey(
            GuideHistoryPageRequest.Direction direction,
            GuideHistoryCursor cursor,
            int count) {}

    private record PageLoad(
            PageKey key,
            long generation,
            List<CompletableFuture<ToolResult<GuideHistoryPage>>> waiters) {}

    private record SessionProjection(int ordinal, GuideModelSelection selection) {}

    private record TimelineKey(UUID requestId, int ordinal) {}

    private record MessageKey(String sessionId, int ordinal) {}

    private record CheckpointKey(String sessionId, int ordinal) {}

    private static final class DurableProjection {
        private String selectedSession;
        private final Map<String, SessionProjection> sessions = new LinkedHashMap<>();
        private final Map<UUID, GuideRequestSnapshot> requests = new LinkedHashMap<>();
        private final Map<TimelineKey, GuideTimelineEntry> timeline = new LinkedHashMap<>();
        private final Map<UUID, List<GuideSource>> sources = new LinkedHashMap<>();
        private final Map<MessageKey, GuideMessage> messages = new LinkedHashMap<>();
        private final Map<CheckpointKey, ContextCheckpoint> checkpoints = new LinkedHashMap<>();

        private void clear() {
            selectedSession = null;
            sessions.clear();
            requests.clear();
            timeline.clear();
            sources.clear();
            messages.clear();
            checkpoints.clear();
        }

        private void removeRequests(List<GuideRequestSnapshot> removed) {
            Set<UUID> ids = removed.stream()
                    .map(GuideRequestSnapshot::requestId)
                    .collect(java.util.stream.Collectors.toSet());
            ids.forEach(requests::remove);
            ids.forEach(sources::remove);
            timeline.keySet().removeIf(key -> ids.contains(key.requestId()));
        }

        private void removeSession(String sessionId, List<GuideRequestSnapshot> removed) {
            sessions.remove(sessionId);
            removeRequests(removed);
            messages.keySet().removeIf(key -> key.sessionId().equals(sessionId));
            checkpoints.keySet().removeIf(key -> key.sessionId().equals(sessionId));
        }
    }
}
