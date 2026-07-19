package dev.openallay.agent.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.openallay.agent.AgentState;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LiveTraceStoreTest {
    @TempDir java.nio.file.Path temporary;

    @Test
    void preservesFullTraceWhileRedactingConfiguredSecrets() throws Exception {
        String secret = "one-time-test-key";
        JsonObject payload = new JsonObject();
        payload.addProperty("question", "完整问题，不截断");
        payload.addProperty("debug", "authorization=" + secret);
        LiveAgentTrace trace = trace(payload);
        LiveTraceStore store = new LiveTraceStore(temporary, Set.of(secret));

        store.record(trace);
        String encoded = store.encoded(trace.requestId());
        assertTrue(encoded.contains("完整问题，不截断"));
        assertFalse(encoded.contains(secret));
        assertTrue(Files.exists(temporary.resolve(trace.requestId() + ".json")));
    }

    @Test
    void disabledPersistenceWritesNothingAndDoesNotApplyRetention() throws Exception {
        LiveTraceStore store = new LiveTraceStore(null, Set.of());
        LiveAgentTrace first = trace(new JsonObject());
        LiveAgentTrace second = trace(new JsonObject());
        store.record(first);
        store.record(second);

        assertEquals(2, store.ids().size());
        assertTrue(Files.list(temporary).findAny().isEmpty());
    }

    private static LiveAgentTrace trace(JsonObject payload) {
        Instant now = Instant.now();
        return new LiveAgentTrace(
                1, UUID.randomUUID(), UUID.randomUUID(), "main", now, now,
                AgentState.COMPLETED,
                List.of(new LiveTraceEvent("request", 1, payload)),
                "answer", null);
    }
}
