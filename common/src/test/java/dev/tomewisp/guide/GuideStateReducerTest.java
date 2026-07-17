package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentState;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.testing.GroundedTestFixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuideStateReducerTest {
    private final GuideStateReducer reducer = new GuideStateReducer(new Gson());

    @Test
    void reducesStreamingToolsSourcesUsageAndCompletion() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(),
                "main",
                GuideTopology.CLIENT_LOCAL,
                "How?",
                Instant.EPOCH);

        request = reducer.apply(request, new AgentEvent.StateChanged(AgentState.MODEL_WAIT), at(1));
        request = reducer.apply(request, new AgentEvent.ModelProgress(new ModelEvent.TextDelta("Hel")), at(2));
        request = reducer.apply(request, new AgentEvent.ModelProgress(new ModelEvent.ReasoningDelta("secret")), at(3));
        request = reducer.apply(request, new AgentEvent.ToolStarted("tomewisp:get_recipe"), at(4));
        request = reducer.apply(request, new AgentEvent.ToolCompleted(
                "tomewisp:get_recipe", false, groundedResult()), at(5));
        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.UsageUpdate(new ModelUsage(10, 3, 2))), at(6));
        request = reducer.apply(request, new AgentEvent.FinalText("Hello"), at(7));

        assertEquals("Hello", request.assistantText());
        assertEquals(GuideRequestStatus.COMPLETED, request.status());
        assertEquals(GuideToolStatus.SUCCEEDED, request.tools().getFirst().status());
        assertEquals("minecraft:recipe_manager", request.sources().getFirst().evidence().sourceId());
        assertEquals(new ModelUsage(10, 3, 2), request.usage());
    }

    @Test
    void rateLimitIsVisibleAndLateEventsAreSuppressed() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(), "main", GuideTopology.SERVER, "Question", Instant.EPOCH);
        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.RateLimited(1500, 2)), at(1));
        assertEquals(GuideRequestStatus.RATE_LIMITED, request.status());
        assertEquals(1500, request.retryAfterMillis());

        request = reducer.apply(request, new AgentEvent.Failed("agent_cancelled", "cancelled"), at(2));
        GuideRequestSnapshot terminal = request;
        GuideRequestSnapshot late = reducer.apply(
                terminal, new AgentEvent.FinalText("late"), at(3));

        assertEquals(GuideRequestStatus.CANCELLED, terminal.status());
        assertSame(terminal, late);
    }

    private static JsonObject groundedResult() {
        JsonObject result = new JsonObject();
        result.addProperty("status", "success");
        JsonObject value = new JsonObject();
        JsonArray evidence = new JsonArray();
        evidence.add(new Gson().toJsonTree(GroundedTestFixtures.serverEvidence()));
        value.add("evidence", evidence);
        result.add("value", value);
        return result;
    }

    private static Instant at(long seconds) {
        return Instant.EPOCH.plusSeconds(seconds);
    }
}
