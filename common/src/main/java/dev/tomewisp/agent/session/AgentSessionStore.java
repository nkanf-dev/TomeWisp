package dev.tomewisp.agent.session;

import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.tool.ToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentSessionStore {
    public record Lease(
            AgentSessionKey key,
            UUID requestId,
            CancellationSignal cancellation,
            List<ModelMessage> history) {
        public Lease {
            history = List.copyOf(history);
        }
    }

    public record Status(boolean active, UUID requestId, int historyMessages) {}

    private static final class Session {
        private List<ModelMessage> history = List.of();
        private Lease active;
    }

    private final Map<AgentSessionKey, Session> sessions = new HashMap<>();

    public synchronized ToolResult<Lease> reserve(AgentSessionKey key, UUID requestId) {
        Session session = sessions.computeIfAbsent(key, ignored -> new Session());
        if (session.active != null) {
            return new ToolResult.Failure<>(
                    "agent_busy", "An Agent request is already active in this session");
        }
        Lease lease = new Lease(
                key, requestId, new CancellationSignal(), session.history);
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

    public synchronized boolean cancel(AgentSessionKey key) {
        Session session = sessions.get(key);
        return session != null && session.active != null && session.active.cancellation().cancel();
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
