package dev.tomewisp.guide;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.client.ClientEventDispatcher;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.guide.history.GuideHistoryAccess;
import dev.tomewisp.guide.history.GuideHistoryDeleteScope;
import dev.tomewisp.guide.history.GuideHistoryException;
import dev.tomewisp.guide.history.GuideHistoryLoad;
import dev.tomewisp.guide.history.GuideHistoryPartition;
import dev.tomewisp.guide.history.GuideHistoryScope;
import dev.tomewisp.tool.ToolResult;
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
            boolean cancelled = active.topology() == GuideTopology.SERVER
                    ? remote.cancel(active.requestId())
                    : local != null && local.cancel(actor, active.sessionId());
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
            session.requests.forEach(request -> requestSessions.remove(request.requestId()));
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
            session.requests.forEach(request -> requestSessions.remove(request.requestId()));
            session.requests.clear();
            session.messages.clear();
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
        sessions.clear();
        sessions.put("main", new SessionState("main", defaultClientSelection()));
        selectedSession = "main";
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
        session.messages.add(new GuideMessage(
                requestId, GuideMessage.Role.USER, question, now));
        requestSessions.put(requestId, sessionId);
        publish();

        if (topology == GuideTopology.SERVER) {
            List<GuideMessage> previousHistory = List.copyOf(
                    session.messages.subList(0, session.messages.size() - 1));
            if (!remote.ask(
                    requestId,
                    sessionId,
                    question,
                    previousHistory,
                    event -> apply(requestId, event))) {
                apply(requestId, new AgentEvent.Failed(
                        "capability_unavailable", "The connected server rejected the model request"));
                result.complete(new ToolResult.Failure<>(
                        "capability_unavailable", "The connected server rejected the model request"));
                return;
            }
        } else {
            Set<dev.tomewisp.context.ContextCapability> requiredContext;
            try {
                requiredContext = local.requiredContext(capturedSelection.profileId());
            } catch (GuideModelProfileException failure) {
                apply(requestId, new AgentEvent.Failed(failure.code(), failure.getMessage()));
                result.complete(new ToolResult.Failure<>(failure.code(), failure.getMessage()));
                return;
            }
            ToolResult<ToolInvocationContext> captured =
                    contexts.capture(requiredContext, requestId.toString());
            if (captured instanceof ToolResult.Failure<ToolInvocationContext> failure) {
                apply(requestId, new AgentEvent.Failed(failure.code(), failure.message()));
                result.complete(new ToolResult.Failure<>(failure.code(), failure.message()));
                return;
            }
            ToolInvocationContext context =
                    ((ToolResult.Success<ToolInvocationContext>) captured).value();
            try {
                local.ask(
                                capturedSelection.profileId(),
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
                result.complete(new ToolResult.Failure<>("agent_failure", message(failure)));
                return;
            }
        }
        result.complete(new ToolResult.Success<>(requestId));
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
                        session.modelSelection))
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
        CompletableFuture<GuideHistoryLoad> loading;
        try {
            loading = Objects.requireNonNull(
                    history.load(historyScope), "history load future");
        } catch (RuntimeException failure) {
            completeHistoryLoad(null, failure);
            return;
        }
        loading.whenComplete((loaded, failure) ->
                dispatcher.execute(() -> completeHistoryLoad(loaded, failure)));
    }

    private void completeHistoryLoad(GuideHistoryLoad loaded, Throwable failure) {
        if (disconnected) {
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
        if (!loaded.diagnostics().isEmpty()) {
            allowHistoryWrites = false;
            GuideFailure diagnostic = loaded.diagnostics().getFirst();
            persistence = new GuidePersistenceSnapshot(
                    GuidePersistenceSnapshot.State.UNAVAILABLE,
                    0,
                    0,
                    diagnostic);
            publishWithoutSave();
            return;
        }
        if (loaded.partition().isPresent()) {
            GuideHistoryPartition partition = loaded.partition().orElseThrow();
            if (!partition.scope().equals(historyScope)) {
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
            hydrate(partition);
        }
        allowHistoryWrites = true;
        persistence = GuidePersistenceSnapshot.available(0);
        publishWithoutSave();
    }

    private void hydrate(GuideHistoryPartition partition) {
        sessions.clear();
        requestSessions.clear();
        for (GuideSessionSnapshot snapshot : partition.sessions()) {
            GuideModelSelection restored = snapshot.modelSelection();
            SessionState session = new SessionState(snapshot.sessionId(), restored);
            if (restored.kind() == GuideModelSelection.Kind.SERVER) {
                session.lastClientProfileId = defaultClientSelection().profileId();
            }
            session.messages.addAll(snapshot.messages());
            session.requests.addAll(snapshot.requests());
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
        GuideHistoryPartition partition = durablePartition();
        persistence = new GuidePersistenceSnapshot(
                GuidePersistenceSnapshot.State.SAVING,
                generation,
                committed,
                null);
        try {
            CompletableFuture<Void> write = Objects.requireNonNull(
                    history.save(partition), "history save future");
            write.whenComplete((ignored, failure) ->
                    dispatcher.execute(() -> finishHistorySave(generation, failure)));
        } catch (RuntimeException failure) {
            finishHistorySave(generation, failure);
        }
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
                                        tool.activity().presentationLines(),
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
}
