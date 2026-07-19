package dev.openallay.agent.session;

import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelMessage;
import dev.openallay.tool.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentSessionStore {
    public record Lease(
            AgentSessionKey key,
            UUID requestId,
            CancellationSignal cancellation,
            List<ModelMessage> history,
            List<ContextCheckpoint> checkpoints) {
        public Lease {
            history = List.copyOf(history);
            checkpoints = List.copyOf(checkpoints);
        }
    }

    public record Status(boolean active, UUID requestId, int historyMessages) {}

    private static final class Session {
        private List<ModelMessage> history = List.of();
        private List<ContextCheckpoint> checkpoints = List.of();
        private Lease active;
    }

    private final Map<AgentSessionKey, Session> sessions = new HashMap<>();

    public synchronized ToolResult<Lease> reserve(AgentSessionKey key, UUID requestId) {
        return reserve(key, requestId, null, null);
    }

    public synchronized ToolResult<Lease> reserveWithHistory(
            AgentSessionKey key, UUID requestId, List<ModelMessage> history) {
        return reserve(key, requestId, history, List.of());
    }

    private ToolResult<Lease> reserve(
            AgentSessionKey key,
            UUID requestId,
            List<ModelMessage> replacementHistory,
            List<ContextCheckpoint> replacementCheckpoints) {
        Session session = sessions.computeIfAbsent(key, ignored -> new Session());
        if (session.active != null) {
            return new ToolResult.Failure<>(
                    "agent_busy", "An Agent request is already active in this session");
        }
        if (replacementHistory != null) {
            session.history = List.copyOf(replacementHistory);
            session.checkpoints = List.copyOf(replacementCheckpoints);
        }
        Lease lease = new Lease(
                key, requestId, new CancellationSignal(), session.history, session.checkpoints);
        session.active = lease;
        return new ToolResult.Success<>(lease);
    }

    public synchronized boolean finish(Lease lease, List<ModelMessage> history) {
        Session session = sessions.get(lease.key());
        if (session == null || session.active == null
                || !session.active.requestId().equals(lease.requestId())) {
            return false;
        }
        session.history = List.copyOf(history);
        session.active = null;
        return true;
    }

    public synchronized boolean recordCheckpoint(Lease lease, ContextCheckpoint checkpoint) {
        Session session = sessions.get(lease.key());
        if (session == null || session.active == null
                || !session.active.requestId().equals(lease.requestId())) {
            return false;
        }
        java.util.ArrayList<ContextCheckpoint> updated = new java.util.ArrayList<>(session.checkpoints);
        updated.add(checkpoint);
        session.checkpoints = List.copyOf(updated);
        return true;
    }

    public synchronized List<ContextCheckpoint> checkpoints(AgentSessionKey key) {
        Session session = sessions.get(key);
        return session == null ? List.of() : session.checkpoints;
    }

    public synchronized void hydrate(AgentSessionKey key, List<ModelMessage> history) {
        hydrate(key, history, List.of());
    }

    public synchronized void hydrate(
            AgentSessionKey key,
            List<ModelMessage> history,
            List<ContextCheckpoint> checkpoints) {
        Session session = sessions.computeIfAbsent(key, ignored -> new Session());
        if (session.active != null) {
            throw new IllegalStateException("cannot hydrate an active Agent session");
        }
        session.history = List.copyOf(history);
        session.checkpoints = List.copyOf(checkpoints);
    }

    public synchronized boolean cancel(AgentSessionKey key) {
        Session session = sessions.get(key);
        if (session == null || session.active == null) {
            return false;
        }
        Lease cancelled = session.active;
        session.active = null;
        return cancelled.cancellation().cancel();
    }

    public synchronized void clear(AgentSessionKey key) {
        Session session = sessions.remove(key);
        if (session != null && session.active != null) {
            session.active.cancellation().cancel();
        }
    }

    public synchronized void clearActor(UUID actorId) {
        List<AgentSessionKey> keys = sessions.keySet().stream()
                .filter(key -> key.actorId().equals(actorId))
                .toList();
        keys.forEach(this::clear);
    }

    public synchronized List<AgentSessionKey> sessions(UUID actorId) {
        return sessions.keySet().stream()
                .filter(key -> key.actorId().equals(actorId))
                .sorted(java.util.Comparator.comparing(AgentSessionKey::sessionId))
                .toList();
    }

    public synchronized Status status(AgentSessionKey key) {
        Session session = sessions.get(key);
        if (session == null) {
            return new Status(false, null, 0);
        }
        return new Status(
                session.active != null,
                session.active == null ? null : session.active.requestId(),
                session.history.size());
    }
}
