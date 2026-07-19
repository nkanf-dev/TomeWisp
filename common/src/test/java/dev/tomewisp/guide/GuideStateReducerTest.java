package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentState;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.guide.semantic.SemanticInline;
import dev.tomewisp.testing.GroundedTestFixtures;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuideStateReducerTest {
    private final GuideStateReducer reducer = new GuideStateReducer(new Gson());

    @Test
    void rejectsMalformedProgressClocksAndAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new GuideRequestProgress(
                GuideRequestPhase.MODEL_WAIT,
                at(2),
                at(1),
                at(3),
                1,
                null,
                null));
        assertThrows(IllegalArgumentException.class, () -> new GuideRequestProgress(
                GuideRequestPhase.ENDPOINT_WAIT,
                Instant.EPOCH,
                at(1),
                at(2),
                -1,
                at(3),
                null));
        assertThrows(IllegalArgumentException.class, () -> new GuideRequestProgress(
                GuideRequestPhase.ENDPOINT_WAIT,
                Instant.EPOCH,
                at(1),
                at(2),
                1,
                at(1),
                null));
    }

    @Test
    void exposesCompactingStateWithoutAddingPlayerContent() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(), "main", GuideTopology.CLIENT_LOCAL, "How?", Instant.EPOCH);

        request = reducer.apply(
                request, new AgentEvent.StateChanged(AgentState.COMPACTING), at(1));

        assertEquals(GuideRequestStatus.COMPACTING, request.status());
        assertEquals(List.of(), request.timeline());
        assertEquals(GuideRequestPhase.COMPACTING, request.progress().phase());
        assertEquals(at(1), request.progress().phaseStartedAt());
        assertEquals(at(1), request.progress().lastProgressAt());
    }

    @Test
    void tracksRedactedModelLifecycleAndMonotonicProgress() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(), "main", GuideTopology.CLIENT_LOCAL, "How?", Instant.EPOCH);
        assertEquals(GuideRequestPhase.PREPARING, request.progress().phase());
        assertEquals(Instant.EPOCH, request.progress().requestStartedAt());
        assertEquals(Instant.EPOCH, request.progress().phaseStartedAt());
        assertEquals(Instant.EPOCH, request.progress().lastProgressAt());

        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.AttemptStarted(1, 10_000L)), at(1));
        assertEquals(GuideRequestPhase.MODEL_WAIT, request.progress().phase());
        assertEquals(1, request.progress().attempt());
        assertEquals(at(1).plusMillis(10_000), request.progress().deadlineAt());

        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.ResponseStarted()), at(2));
        assertEquals(GuideRequestPhase.RESPONSE_STREAMING, request.progress().phase());
        assertEquals(at(2), request.progress().phaseStartedAt());

        request = reducer.apply(request, text("one"), at(4));
        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.ReasoningDelta("redacted")), at(3));
        assertEquals(at(4), request.progress().lastProgressAt());
        assertEquals("one", request.assistantText());

        request = reducer.apply(request, new AgentEvent.ToolStarted(
                "call-1", "tomewisp:get_recipe"), at(5));
        assertEquals(GuideRequestPhase.TOOL_WAIT, request.progress().phase());
        request = reducer.apply(request, new AgentEvent.FinalText("done"), at(6));
        assertEquals(GuideRequestPhase.COMPLETING, request.progress().phase());
        assertEquals(at(6), request.progress().lastProgressAt());
        assertTrue(request.terminal());
    }

    @Test
    void rateLimitPublishesRetryTimeAndClearsTheAttemptDeadline() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(), "main", GuideTopology.CLIENT_LOCAL, "How?", Instant.EPOCH);
        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.AttemptStarted(2, 10_000L)), at(1));

        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.RateLimited(1500, 2)), at(2));

        assertEquals(GuideRequestPhase.ENDPOINT_WAIT, request.progress().phase());
        assertEquals(2, request.progress().attempt());
        assertEquals(at(2).plusMillis(1500), request.progress().retryAt());
        assertEquals(null, request.progress().deadlineAt());
    }

    @Test
    void snapshotDerivesFinalTextAndToolsFromChronologicalTimeline() {
        UUID requestId = UUID.randomUUID();
        GuideToolActivity tool = new GuideToolActivity(
                "call-1",
                0,
                "tomewisp:get_recipe",
                GuideToolStatus.SUCCEEDED,
                groundedResult(),
                List.of(GuideToolMessage.of(GuideToolMessage.Key.RECIPE_UNAVAILABLE)),
                List.of());
        GuideRequestSnapshot request = new GuideRequestSnapshot(
                requestId,
                "main",
                GuideTopology.CLIENT_LOCAL,
                "How?",
                List.of(
                        new GuideTimelineEntry.Assistant(0, "I will check.", false, List.of()),
                        new GuideTimelineEntry.Tool(1, tool),
                        new GuideTimelineEntry.Assistant(
                                2, "You need nine ingots.", false, List.of())),
                GuideRequestStatus.COMPLETED,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(3),
                Instant.EPOCH.plusSeconds(3));

        assertEquals(List.of(0, 1, 2), request.timeline().stream()
                .map(GuideTimelineEntry::ordinal)
                .toList());
        assertEquals("You need nine ingots.", request.assistantText());
        assertEquals(List.of(tool), request.tools());
    }

    @Test
    void reducesAssistantToolsAndContinuationsInChronologicalOrder() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(),
                "main",
                GuideTopology.CLIENT_LOCAL,
                "How?",
                Instant.EPOCH);

        request = reducer.apply(request, new AgentEvent.StateChanged(AgentState.MODEL_WAIT), at(1));
        request = reducer.apply(request, text("I will inspect the recipe."), at(2));
        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.ReasoningDelta("secret")), at(3));
        request = reducer.apply(request, new AgentEvent.ToolStarted(
                "call-1", "tomewisp:get_recipe"), at(4));
        request = reducer.apply(request, new AgentEvent.ToolCompleted(
                "call-1", "tomewisp:get_recipe", false, groundedResult()), at(5));
        request = reducer.apply(request, text("Now I will inspect inventory."), at(6));
        request = reducer.apply(request, new AgentEvent.ToolStarted(
                "call-2", "tomewisp:inspect_inventory"), at(7));
        request = reducer.apply(request, new AgentEvent.ToolCompleted(
                "call-2", "tomewisp:inspect_inventory", false, groundedResult()), at(8));
        request = reducer.apply(request, text("You are missing five ingots."), at(9));
        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.UsageUpdate(new ModelUsage(10, 3, 2))), at(10));
        request = reducer.apply(request, new AgentEvent.FinalText(
                "You are missing five ingots."), at(11));

        assertEquals(
                List.of(
                        GuideTimelineEntry.Assistant.class,
                        GuideTimelineEntry.Tool.class,
                        GuideTimelineEntry.Assistant.class,
                        GuideTimelineEntry.Tool.class,
                        GuideTimelineEntry.Assistant.class),
                request.timeline().stream().map(Object::getClass).toList());
        assertEquals(List.of(0, 1, 2, 3, 4), request.timeline().stream()
                .map(GuideTimelineEntry::ordinal)
                .toList());
        assertEquals("I will inspect the recipe.",
                ((GuideTimelineEntry.Assistant) request.timeline().get(0)).text());
        assertEquals("Now I will inspect inventory.",
                ((GuideTimelineEntry.Assistant) request.timeline().get(2)).text());
        assertEquals("You are missing five ingots.", request.assistantText());
        assertEquals(GuideRequestStatus.COMPLETED, request.status());
        assertEquals(GuideToolStatus.SUCCEEDED, request.tools().getFirst().status());
        assertEquals(List.of(GuideToolMessage.of(GuideToolMessage.Key.RECIPE_UNAVAILABLE)),
                request.tools().getFirst().presentationMessages());
        assertEquals(List.of("call-1", "call-2"), request.tools().stream()
                .map(GuideToolActivity::invocationId)
                .toList());
        assertEquals("minecraft:recipe_manager", request.sources().getFirst().evidence().sourceId());
        assertEquals(new ModelUsage(10, 3, 2), request.usage());
    }

    @Test
    void repeatedToolNamesRemainDistinctByInvocationId() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(), "main", GuideTopology.CLIENT_LOCAL, "How?", Instant.EPOCH);

        request = reducer.apply(request, new AgentEvent.ToolStarted(
                "call-1", "tomewisp:get_recipe"), at(1));
        request = reducer.apply(request, new AgentEvent.ToolCompleted(
                "call-1", "tomewisp:get_recipe", false, groundedResult()), at(2));
        request = reducer.apply(request, new AgentEvent.ToolStarted(
                "call-2", "tomewisp:get_recipe"), at(3));
        request = reducer.apply(request, new AgentEvent.ToolCompleted(
                "call-2", "tomewisp:get_recipe", false, groundedResult()), at(4));

        assertEquals(List.of("call-1", "call-2"), request.tools().stream()
                .map(GuideToolActivity::invocationId)
                .toList());
    }

    @Test
    void semanticSegmentsAreIndependentAndOnlyLaterTextCanUseToolHandles() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(), "main", GuideTopology.CLIENT_LOCAL, "How?", Instant.EPOCH);

        request = reducer.apply(request, text("**Before lookup**"), at(1));
        request = reducer.apply(request, new AgentEvent.ToolStarted(
                "call-1", "tomewisp:get_recipe"), at(2));
        request = reducer.apply(request, new AgentEvent.ToolCompleted(
                "call-1", "tomewisp:get_recipe", false, referenceResult()), at(3));
        request = reducer.apply(request, text("Use [[tw:item|minecraft:iron_"), at(4));
        request = reducer.apply(request, text("block|Iron block]]."), at(5));
        request = reducer.apply(request, new AgentEvent.FinalText(
                "Use [[tw:item|minecraft:iron_block|Iron block]]."), at(6));

        GuideTimelineEntry.Assistant before =
                (GuideTimelineEntry.Assistant) request.timeline().get(0);
        GuideTimelineEntry.Assistant after =
                (GuideTimelineEntry.Assistant) request.timeline().get(2);
        assertEquals("Before lookup", before.semantic().fallbackText());
        assertFalse(hasReference(before));
        assertEquals("Use Iron block.", after.semantic().fallbackText());
        assertTrue(hasReference(after));
        assertEquals("call-1", reference(after).originInvocationId());
    }

    @Test
    void finalTextReconcilesOnlyTheLastSemanticSegment() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(), "main", GuideTopology.CLIENT_LOCAL, "How?", Instant.EPOCH);
        request = reducer.apply(request, text("# First"), at(1));
        request = reducer.apply(request, new AgentEvent.ToolStarted(
                "call-1", "tomewisp:get_recipe"), at(2));
        request = reducer.apply(request, new AgentEvent.ToolCompleted(
                "call-1", "tomewisp:get_recipe", false, groundedResult()), at(3));
        request = reducer.apply(request, text("Partial"), at(4));
        GuideTimelineEntry.Assistant firstBefore =
                (GuideTimelineEntry.Assistant) request.timeline().getFirst();

        request = reducer.apply(request, new AgentEvent.FinalText("## Final"), at(5));

        GuideTimelineEntry.Assistant firstAfter =
                (GuideTimelineEntry.Assistant) request.timeline().getFirst();
        GuideTimelineEntry.Assistant last =
                (GuideTimelineEntry.Assistant) request.timeline().getLast();
        assertSame(firstBefore.semantic(), firstAfter.semantic());
        assertEquals("First", firstAfter.semantic().fallbackText());
        assertEquals("Final", last.semantic().fallbackText());
        assertEquals("## Final", last.text());
    }

    @Test
    void missingToolInvocationFailsRequestClosed() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(), "main", GuideTopology.CLIENT_LOCAL, "How?", Instant.EPOCH);

        request = reducer.apply(request, new AgentEvent.ToolCompleted(
                "missing", "tomewisp:get_recipe", false, groundedResult()), at(1));

        assertEquals(GuideRequestStatus.FAILED, request.status());
        assertEquals("timeline_protocol_error", request.failure().code());
        assertEquals(at(1), request.terminalAt());
        assertEquals(List.of(), request.timeline());
    }

    @Test
    void rateLimitIsVisibleAndLateEventsAreSuppressed() {
        GuideRequestSnapshot request = GuideRequestSnapshot.start(
                UUID.randomUUID(), "main", GuideTopology.SERVER, "Question", Instant.EPOCH);
        request = reducer.apply(request, new AgentEvent.ModelProgress(
                new ModelEvent.RateLimited(1500, 2)), at(1));
        assertEquals(GuideRequestStatus.RATE_LIMITED, request.status());
        assertEquals(1500, request.retryAfterMillis());

        request = reducer.apply(
                request, new AgentEvent.StateChanged(AgentState.CANCELLED), at(3));
        request = reducer.apply(request, new AgentEvent.Failed("agent_cancelled", "cancelled"), at(3));
        GuideRequestSnapshot terminal = request;
        GuideRequestSnapshot late = reducer.apply(
                terminal, new AgentEvent.FinalText("late"), at(4));

        assertEquals(GuideRequestStatus.CANCELLED, terminal.status());
        assertEquals(null, terminal.progress().retryAt());
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

    private static JsonObject referenceResult() {
        JsonObject result = groundedResult();
        JsonObject value = result.getAsJsonObject("value");
        JsonObject recipe = new JsonObject();
        recipe.addProperty("sourceId", "minecraft:recipe_manager");
        recipe.addProperty("generation", GroundedTestFixtures.RECIPE_GENERATION);
        recipe.addProperty("recipeId", "minecraft:iron_block");
        recipe.addProperty("itemId", "minecraft:iron_block");
        value.add("recipe", recipe);
        return result;
    }

    private static boolean hasReference(GuideTimelineEntry.Assistant assistant) {
        return assistant.semantic().blocks().stream()
                .filter(dev.tomewisp.guide.semantic.SemanticBlock.Paragraph.class::isInstance)
                .map(dev.tomewisp.guide.semantic.SemanticBlock.Paragraph.class::cast)
                .flatMap(block -> block.content().stream())
                .anyMatch(SemanticInline.Reference.class::isInstance);
    }

    private static dev.tomewisp.guide.semantic.SemanticReference reference(
            GuideTimelineEntry.Assistant assistant) {
        return assistant.semantic().blocks().stream()
                .filter(dev.tomewisp.guide.semantic.SemanticBlock.Paragraph.class::isInstance)
                .map(dev.tomewisp.guide.semantic.SemanticBlock.Paragraph.class::cast)
                .flatMap(block -> block.content().stream())
                .filter(SemanticInline.Reference.class::isInstance)
                .map(SemanticInline.Reference.class::cast)
                .map(SemanticInline.Reference::reference)
                .findFirst().orElseThrow();
    }

    private static AgentEvent text(String value) {
        return new AgentEvent.ModelProgress(new ModelEvent.TextDelta(value));
    }

    private static Instant at(long seconds) {
        return Instant.EPOCH.plusSeconds(seconds);
    }
}
