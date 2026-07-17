package dev.tomewisp.guide;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.client.ClientEventDispatcher;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Single client product state source for commands and screens. */
public final class GuideService {
    private final UUID actor;
    private final GuideLocalEndpoint local;
    private final GuideRemoteEndpoint remote;
    private final GuideContextProvider contexts;
    private final ClientEventDispatcher dispatcher;
    private final Clock clock;
    private final GuideStateReducer reducer;
    private final Map<String, SessionState> sessions = new LinkedHashMap<>();
    private final Map<UUID, String> requestSessions = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Consumer<GuideSnapshot>> listeners =
            new CopyOnWriteArrayList<>();
    private volatile GuideSnapshot snapshot;
    private String selectedSession = "main";
    private GuideModelMode modelMode = GuideModelMode.CLIENT;

    public GuideService(
            UUID actor,
            GuideLocalEndpoint local,
            GuideRemoteEndpoint remote,
            GuideContextProvider contexts,
            ClientEventDispatcher dispatcher,
            Clock clock,
            Gson gson) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.local = local;
        this.remote = Objects.requireNonNull(remote, "remote");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        reducer = new GuideStateReducer(gson);
        sessions.put("main", new SessionState("main"));
        snapshot = buildSnapshot();
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
            GuideRequestSnapshot request = find(requestId);
            if (request == null || !request.terminal()
                    || (request.status() != GuideRequestStatus.FAILED
                            && request.status() != GuideRequestStatus.CANCELLED)) {
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
            if (!validSession(sessionId)) {
                result.complete(new ToolResult.Failure<>(
                        "invalid_session", "Session ID may contain letters, numbers, dot, underscore, and dash"));
                return;
            }
            sessions.computeIfAbsent(sessionId, SessionState::new);
            selectedSession = sessionId;
            publish();
            result.complete(new ToolResult.Success<>(sessionId));
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> closeSession(String sessionId) {
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
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
                sessions.put("main", new SessionState("main"));
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
            if (mode == GuideModelMode.SERVER && !remote.serverModelAvailable()) {
                result.complete(new ToolResult.Failure<>(
                        "capability_unavailable", "The connected server does not provide a model"));
                return;
            }
            modelMode = Objects.requireNonNull(mode, "mode");
            publish();
            result.complete(new ToolResult.Success<>(mode));
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
            publish();
            result.complete(null);
        });
        return result;
    }

    public CompletableFuture<Void> disconnect() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        dispatcher.execute(() -> {
            for (SessionState session : sessions.values()) {
                GuideRequestSnapshot active = active(session);
                if (active != null) {
                    if (active.topology() == GuideTopology.SERVER) {
                        remote.cancel(active.requestId());
                    } else if (local != null) {
                        local.cancel(actor, session.id);
                    }
                }
            }
            if (local != null) {
                local.clearActor(actor);
            }
            remote.disconnect();
            requestSessions.clear();
            sessions.clear();
            sessions.put("main", new SessionState("main"));
            selectedSession = "main";
            modelMode = GuideModelMode.CLIENT;
            publish();
            result.complete(null);
        });
        return result;
    }

    public CompletableFuture<Void> shutdown() {
        return disconnect();
    }

    private void submit(
            String sessionId, String question, CompletableFuture<ToolResult<UUID>> result) {
        if (question == null || question.isBlank()) {
            result.complete(new ToolResult.Failure<>(
                    "invalid_arguments", "Question must not be blank"));
            return;
        }
        SessionState session = sessions.computeIfAbsent(sessionId, SessionState::new);
        if (active(session) != null) {
            result.complete(new ToolResult.Failure<>(
                    "agent_busy", "This guide session already has an active request"));
            return;
        }
        GuideTopology topology;
        if (modelMode == GuideModelMode.SERVER) {
            if (!remote.serverModelAvailable()) {
                result.complete(new ToolResult.Failure<>(
                        "capability_unavailable", "The connected server does not provide a model"));
                return;
            }
            topology = GuideTopology.SERVER;
        } else {
            if (local == null) {
                result.complete(new ToolResult.Failure<>(
                        "model_not_configured", "No usable client model is configured"));
                return;
            }
            topology = remote.serverToolsAvailable()
                    ? GuideTopology.CLIENT_WITH_SERVER_TOOLS
                    : GuideTopology.CLIENT_LOCAL;
        }

        UUID requestId = UUID.randomUUID();
        Instant now = clock.instant();
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                requestId, sessionId, topology, question, now);
        session.requests.add(request);
        session.messages.add(new GuideMessage(
                requestId, GuideMessage.Role.USER, question, now));
        requestSessions.put(requestId, sessionId);
        publish();

        if (topology == GuideTopology.SERVER) {
            if (!remote.ask(requestId, sessionId, question, event -> apply(requestId, event))) {
                apply(requestId, new AgentEvent.Failed(
                        "capability_unavailable", "The connected server rejected the model request"));
                result.complete(new ToolResult.Failure<>(
                        "capability_unavailable", "The connected server rejected the model request"));
                return;
            }
        } else {
            ToolResult<ToolInvocationContext> captured =
                    contexts.capture(local.requiredContext(), requestId.toString());
            if (captured instanceof ToolResult.Failure<ToolInvocationContext> failure) {
                apply(requestId, new AgentEvent.Failed(failure.code(), failure.message()));
                result.complete(new ToolResult.Failure<>(failure.code(), failure.message()));
                return;
            }
            ToolInvocationContext context =
                    ((ToolResult.Success<ToolInvocationContext>) captured).value();
            try {
                local.ask(actor, sessionId, requestId, question, context, event -> apply(requestId, event))
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
        GuideRequestSnapshot before = session.requests.get(index);
        GuideRequestSnapshot after = reducer.apply(before, event, clock.instant());
        if (before == after) {
            return;
        }
        session.requests.set(index, after);
        if (after.terminal() && !after.assistantText().isBlank()) {
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
            apply(requestId, new AgentEvent.Failed("agent_failure", message(throwable)));
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
                        session.id, session.messages, session.requests))
                .toList();
        return new GuideSnapshot(
                actor,
                selectedSession,
                modelMode,
                local != null,
                remote.serverModelAvailable(),
                copies,
                clock.instant());
    }

    private static boolean validSession(String value) {
        return value != null && value.matches("[a-zA-Z0-9_.-]+");
    }

    private static String message(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private static final class SessionState {
        private final String id;
        private final List<GuideMessage> messages = new ArrayList<>();
        private final List<GuideRequestSnapshot> requests = new ArrayList<>();

        private SessionState(String id) {
            if (!validSession(id)) {
                throw new IllegalArgumentException("invalid sessionId");
            }
            this.id = id;
        }
    }
}
