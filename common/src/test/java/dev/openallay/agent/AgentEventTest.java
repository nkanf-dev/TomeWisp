package dev.openallay.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

final class AgentEventTest {
    @Test
    void toolEventsRetainInvocationIdentityAndDefensivelyCopyResults() {
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "success");
        AgentEvent.ToolStarted started =
                new AgentEvent.ToolStarted("model-call-7", "openallay:test");
        AgentEvent.ToolCompleted completed = new AgentEvent.ToolCompleted(
                "model-call-7", "openallay:test", false, normalized);

        normalized.addProperty("mutated", true);
        JsonObject read = completed.normalized();
        read.addProperty("alsoMutated", true);

        assertEquals("model-call-7", started.invocationId());
        assertEquals("model-call-7", completed.invocationId());
        assertEquals("success", completed.normalized().get("status").getAsString());
        assertEquals(1, completed.normalized().size());
        assertFalse(completed.normalized().has("mutated"));
    }

    @Test
    void modelProgressRequiresAClosedLifecycleOrContentEvent() {
        assertThrows(NullPointerException.class, () -> new AgentEvent.ModelProgress(null));
    }
}
