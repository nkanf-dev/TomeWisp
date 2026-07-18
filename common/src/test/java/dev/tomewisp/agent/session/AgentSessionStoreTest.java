package dev.tomewisp.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AgentSessionStoreTest {
    @Test
    void rejectsConcurrentWorkAndPreservesCompletedHistory() {
        AgentSessionStore store = new AgentSessionStore();
        UUID actor = UUID.randomUUID();
        AgentSessionKey key = new AgentSessionKey(actor, "main");
        AgentSessionStore.Lease lease = success(store.reserve(key, UUID.randomUUID())).value();
        ToolResult.Failure<AgentSessionStore.Lease> busy = failure(
                store.reserve(key, UUID.randomUUID()));
        assertEquals("agent_busy", busy.code());

        List<ModelMessage> history = List.of(ModelMessage.userText("first"));
        assertTrue(store.finish(lease, history));
        AgentSessionStore.Lease next = success(store.reserve(key, UUID.randomUUID())).value();
        assertEquals(history, next.history());
    }

    @Test
    void cancelAndClearAreRequestScoped() {
        AgentSessionStore store = new AgentSessionStore();
        UUID actor = UUID.randomUUID();
        AgentSessionKey key = new AgentSessionKey(actor, "main");
        AgentSessionStore.Lease lease = success(store.reserve(key, UUID.randomUUID())).value();
        assertTrue(store.cancel(key));
        assertTrue(lease.cancellation().isCancelled());
        assertInstanceOf(ToolResult.Success.class, store.reserve(key, UUID.randomUUID()));
        assertFalse(store.cancel(new AgentSessionKey(UUID.randomUUID(), "main")));
        store.clear(key);
        assertFalse(store.status(key).active());
        assertEquals(0, store.status(key).historyMessages());
    }

    @Test
    void differentSessionsForOneActorCanBeActiveTogether() {
        AgentSessionStore store = new AgentSessionStore();
        UUID actor = UUID.randomUUID();
        AgentSessionKey first = new AgentSessionKey(actor, "main");
        AgentSessionKey second = new AgentSessionKey(actor, "automation");

        assertInstanceOf(ToolResult.Success.class, store.reserve(first, UUID.randomUUID()));
        assertInstanceOf(ToolResult.Success.class, store.reserve(second, UUID.randomUUID()));
        assertEquals(List.of(second, first), store.sessions(actor));

        store.clearActor(actor);
        assertTrue(store.sessions(actor).isEmpty());
    }

    @Test
    void restoredHistoryIsInstalledOnlyWhenItsLeaseWins() {
        AgentSessionStore store = new AgentSessionStore();
        AgentSessionKey key = new AgentSessionKey(UUID.randomUUID(), "main");
        List<ModelMessage> firstHistory = List.of(ModelMessage.userText("first"));
        AgentSessionStore.Lease first = success(
                store.reserveWithHistory(key, UUID.randomUUID(), firstHistory)).value();

        ToolResult.Failure<AgentSessionStore.Lease> busy = failure(
                store.reserveWithHistory(
                        key, UUID.randomUUID(), List.of(ModelMessage.userText("second"))));

        assertEquals("agent_busy", busy.code());
        assertEquals(firstHistory, first.history());
        assertTrue(store.finish(first, firstHistory));
        assertEquals(firstHistory, success(store.reserve(key, UUID.randomUUID())).value().history());
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<AgentSessionStore.Lease> success(
            ToolResult<AgentSessionStore.Lease> result) {
        return (ToolResult.Success<AgentSessionStore.Lease>)
                assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<AgentSessionStore.Lease> failure(
            ToolResult<AgentSessionStore.Lease> result) {
        return (ToolResult.Failure<AgentSessionStore.Lease>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
