package dev.tomewisp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

final class AgentEventTest {
    @Test
    void completedToolResultIsDefensivelyCopied() {
        JsonObject normalized = new JsonObject();
        normalized.addProperty("status", "success");
        AgentEvent.ToolCompleted completed =
                new AgentEvent.ToolCompleted("tomewisp:test", false, normalized);

        normalized.addProperty("mutated", true);
        JsonObject read = completed.normalized();
        read.addProperty("alsoMutated", true);

        assertEquals("success", completed.normalized().get("status").getAsString());
        assertEquals(1, completed.normalized().size());
    }
}
