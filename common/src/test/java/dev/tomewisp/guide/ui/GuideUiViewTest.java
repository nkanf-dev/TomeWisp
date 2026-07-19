package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideClientModelProfile;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideModelSelection;
import dev.tomewisp.guide.GuidePersistenceSnapshot;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestPhase;
import dev.tomewisp.guide.GuideRequestProgress;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolMessage;
import dev.tomewisp.guide.GuideToolStatus;
import dev.tomewisp.model.ModelUsage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuideUiViewTest {
    private static final UUID ACTOR = UUID.fromString("30ab22ed-23fb-46f2-82ca-d4a656698eec");
    private static final UUID REQUEST = UUID.fromString("6b4cbcf4-a951-4ded-beb8-165846941b8c");

    @Test
    void oneAssistantProjectionReconcilesStreamingAndFinalWithoutReasoningSurface() {
        GuideUiView streaming = GuideUiView.from(snapshot(request(
                GuideRequestStatus.MODEL_WAIT, "partial", null, null)));
        assertEquals(1, streaming.rows().stream().filter(GuideUiRow.Assistant.class::isInstance).count());
        assertTrue(((GuideUiRow.Assistant) streaming.rows().get(1)).streaming());
        assertTrue(streaming.canCancel());
        assertFalse(streaming.canSend());
        assertEquals(GuideRequestPhase.MODEL_WAIT, streaming.progress().phase());
        assertEquals("screen.tomewisp.progress.model_wait",
                streaming.progress().activityTranslationKey());

        GuideUiView completed = GuideUiView.from(snapshot(request(
                GuideRequestStatus.COMPLETED, "complete", Instant.EPOCH.plusSeconds(2), null)));
        assertEquals(1, completed.rows().stream().filter(GuideUiRow.Assistant.class::isInstance).count());
        assertEquals("complete", ((GuideUiRow.Assistant) completed.rows().get(1)).text());
        assertEquals("complete", ((GuideUiRow.Assistant) completed.rows().get(1))
                .semantic().fallbackText());
        assertFalse(((GuideUiRow.Assistant) completed.rows().get(1)).streaming());
        assertTrue(completed.progress() == null);
    }

    @Test
    void activeProgressProjectionKeepsOnlyRedactedTimingAndAttemptState() {
        Instant started = Instant.parse("2026-07-19T00:00:00Z");
        GuideRequestProgress progress = new GuideRequestProgress(
                GuideRequestPhase.RESPONSE_STREAMING,
                started,
                started.plusSeconds(3),
                started.plusSeconds(9),
                2,
                null,
                started.plusSeconds(300));
        GuideRequestSnapshot active = new GuideRequestSnapshot(
                REQUEST,
                "main",
                GuideTopology.CLIENT_LOCAL,
                "private player text",
                List.of(),
                GuideRequestStatus.MODEL_WAIT,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                started,
                started.plusSeconds(9),
                null,
                GuideModelSelection.client("default"),
                progress);

        GuideUiProgress projected = GuideUiView.from(snapshot(active)).progress();

        assertEquals(GuideRequestPhase.RESPONSE_STREAMING, projected.phase());
        assertEquals(2, projected.attempt());
        assertEquals(started.plusSeconds(9), projected.lastProgressAt());
        assertEquals(started.plusSeconds(300), projected.deadlineAt());
        assertFalse(projected.toString().contains("private player text"));
    }

    @Test
    void rateLimitUsesFixedProgressSurfaceInsteadOfMovingTranscriptRow() {
        Instant started = Instant.parse("2026-07-19T00:00:00Z");
        GuideRequestProgress progress = new GuideRequestProgress(
                GuideRequestPhase.ENDPOINT_WAIT,
                started,
                started.plusSeconds(1),
                started.plusSeconds(1),
                2,
                started.plusSeconds(29),
                null);
        GuideRequestSnapshot limited = new GuideRequestSnapshot(
                REQUEST,
                "main",
                GuideTopology.CLIENT_LOCAL,
                "question",
                List.of(),
                GuideRequestStatus.RATE_LIMITED,
                List.of(),
                ModelUsage.empty(),
                28_000L,
                null,
                started,
                started.plusSeconds(1),
                null,
                GuideModelSelection.client("default"),
                progress);

        GuideUiView view = GuideUiView.from(snapshot(limited));

        assertEquals(GuideRequestPhase.ENDPOINT_WAIT, view.progress().phase());
        assertEquals(started.plusSeconds(29), view.progress().retryAt());
        assertFalse(view.rows().stream().anyMatch(GuideUiRow.Status.class::isInstance));
    }

    @Test
    void unavailableModeAndFailedRequestExposeActionsWithoutFabricatingAnswer() {
        GuideRequestSnapshot failed = request(
                GuideRequestStatus.FAILED,
                "",
                Instant.EPOCH.plusSeconds(2),
                new GuideFailure("provider_error", "provider unavailable"));
        GuideSnapshot snapshot = new GuideSnapshot(
                ACTOR, "main", GuideModelMode.CLIENT, false, true,
                List.of(new GuideSessionSnapshot("main", List.<GuideMessage>of(), List.of(failed))),
                Instant.EPOCH.plusSeconds(2));
        GuideUiView view = GuideUiView.from(snapshot);
        assertFalse(view.canSend());
        assertTrue(view.canRetry());
        assertTrue(view.capabilityMessage().contains("未配置"));
        assertEquals(0, view.rows().stream().filter(GuideUiRow.Assistant.class::isInstance).count());
        assertEquals(1, view.rows().stream().filter(GuideUiRow.Status.class::isInstance).count());
    }

    @Test
    void projectsInterleavedTimelineInStoredOrder() {
        GuideToolActivity recipe = new GuideToolActivity(
                "call-1", 0, "tomewisp:get_recipe",
                GuideToolStatus.SUCCEEDED, null, List.of(), List.of());
        GuideToolActivity inventory = new GuideToolActivity(
                "call-2", 1, "tomewisp:inspect_inventory",
                GuideToolStatus.SUCCEEDED, null, List.of(), List.of());
        GuideRequestSnapshot request = new GuideRequestSnapshot(
                REQUEST,
                "main",
                GuideTopology.CLIENT_LOCAL,
                "question",
                List.of(
                        new GuideTimelineEntry.Assistant(0, "Checking recipe.", false, List.of()),
                        new GuideTimelineEntry.Tool(1, recipe),
                        new GuideTimelineEntry.Assistant(2, "Checking inventory.", false, List.of()),
                        new GuideTimelineEntry.Tool(3, inventory),
                        new GuideTimelineEntry.Assistant(4, "Final answer.", false, List.of())),
                GuideRequestStatus.COMPLETED,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(5),
                Instant.EPOCH.plusSeconds(5));

        GuideUiView view = GuideUiView.from(snapshot(request));

        assertEquals(
                List.of(
                        GuideUiRow.User.class,
                        GuideUiRow.Assistant.class,
                        GuideUiRow.Tool.class,
                        GuideUiRow.Assistant.class,
                        GuideUiRow.Tool.class,
                        GuideUiRow.Assistant.class),
                view.rows().stream().map(Object::getClass).toList());
        assertEquals(List.of(0, 1, 2, 3, 4), view.rows().stream()
                .filter(row -> !(row instanceof GuideUiRow.User))
                .map(row -> switch (row) {
                    case GuideUiRow.Assistant assistant -> assistant.ordinal();
                    case GuideUiRow.Tool tool -> tool.ordinal();
                    case GuideUiRow.Persistence ignored -> -1;
                    case GuideUiRow.Status ignored -> -1;
                    case GuideUiRow.User ignored -> -1;
                })
                .toList());
    }

    @Test
    void toolRowsKeepDiagnosticsOutOfDefaultViewAndGateThemInDebugMode() {
        GuideToolActivity tool = new GuideToolActivity(
                "call-private",
                0,
                "tomewisp:inspect_inventory",
                GuideToolStatus.SUCCEEDED,
                JsonParser.parseString("""
                        {"status":"success","value":{"counts":{"minecraft:apple":3}}}
                        """).getAsJsonObject(),
                List.of(GuideToolMessage.of(
                        GuideToolMessage.Key.INVENTORY_ITEM,
                        "minecraft:apple",
                        "3")),
                List.of());
        GuideRequestSnapshot request = new GuideRequestSnapshot(
                REQUEST,
                "main",
                GuideTopology.CLIENT_LOCAL,
                "question",
                List.of(new GuideTimelineEntry.Tool(0, tool)),
                GuideRequestStatus.COMPLETED,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                Instant.EPOCH.plusSeconds(1));

        GuideUiRow.Tool normal = (GuideUiRow.Tool) GuideUiView.from(snapshot(request))
                .rows().get(1);
        assertTrue(normal.detail().debug().isEmpty());

        GuideUiRow.Tool debug = (GuideUiRow.Tool) GuideUiView.from(
                snapshot(request), new GuideDisplayConfig(
                        GuideDisplayConfig.SCHEMA_VERSION, true, true)).rows().get(1);
        assertEquals("call-private", debug.detail().debug().orElseThrow().invocationId());
    }

    @Test
    void persistenceHealthControlsSubmissionWithoutReorderingTimeline() {
        GuideRequestSnapshot completed = request(
                GuideRequestStatus.COMPLETED,
                "answer",
                Instant.EPOCH.plusSeconds(2),
                null);
        GuideUiView loading = GuideUiView.from(snapshot(
                completed, GuidePersistenceSnapshot.loading()));
        assertFalse(loading.canSend());
        assertEquals(GuidePersistenceSnapshot.State.LOADING,
                ((GuideUiRow.Persistence) loading.rows().getFirst()).state());

        GuideUiView saving = GuideUiView.from(snapshot(
                completed,
                new GuidePersistenceSnapshot(
                        GuidePersistenceSnapshot.State.SAVING, 2, 1, null)));
        assertEquals(
                List.of(
                        GuideUiRow.Persistence.class,
                        GuideUiRow.User.class,
                        GuideUiRow.Assistant.class),
                saving.rows().stream().map(Object::getClass).toList());

        GuideUiView unavailable = GuideUiView.from(snapshot(
                completed,
                new GuidePersistenceSnapshot(
                        GuidePersistenceSnapshot.State.UNAVAILABLE,
                        2,
                        1,
                        new GuideFailure("history_write_failed", "not durable"))));
        assertTrue(unavailable.canSend());
        assertEquals("history_write_failed",
                ((GuideUiRow.Persistence) unavailable.rows().getFirst()).failure().code());

        GuideUiView available = GuideUiView.from(snapshot(
                completed, GuidePersistenceSnapshot.available(2)));
        assertFalse(available.rows().stream().anyMatch(GuideUiRow.Persistence.class::isInstance));
    }

    @Test
    void interruptedRequestIsVisibleAndRetryable() {
        GuideRequestSnapshot interrupted = request(
                GuideRequestStatus.INTERRUPTED,
                "partial",
                Instant.EPOCH.plusSeconds(2),
                new GuideFailure("request_interrupted", "interrupted"));

        GuideUiView view = GuideUiView.from(snapshot(interrupted));

        assertTrue(view.canRetry());
        assertTrue(view.rows().stream()
                .filter(GuideUiRow.Status.class::isInstance)
                .map(GuideUiRow.Status.class::cast)
                .anyMatch(row -> row.status() == GuideRequestStatus.INTERRUPTED));
    }

    @Test
    void modelChoicesKeepProfileOrderAndExposeUnavailableRememberedSelection() {
        GuideModelSelection remembered = GuideModelSelection.client("removed");
        GuideSnapshot snapshot = new GuideSnapshot(
                ACTOR,
                "main",
                GuideModelMode.CLIENT,
                true,
                true,
                GuidePersistenceSnapshot.disabled(),
                List.of(new GuideSessionSnapshot(
                        "main", List.of(), List.of(), List.of(), remembered)),
                Instant.EPOCH,
                remembered,
                List.of(
                        new GuideClientModelProfile(
                                "a", "Model A", true, true, "provider/a", null),
                        new GuideClientModelProfile(
                                "disabled", "Disabled", false, false, "provider/disabled",
                                new GuideFailure("model_disabled", "disabled")),
                        new GuideClientModelProfile(
                                "broken", "Broken", true, false, "provider/broken",
                                new GuideFailure("invalid_model_config", "invalid"))));

        GuideUiView view = GuideUiView.from(snapshot);

        assertEquals(
                List.of(
                        GuideModelSelection.client("a"),
                        GuideModelSelection.client("broken"),
                        GuideModelSelection.client("removed"),
                        GuideModelSelection.server()),
                view.modelChoices().stream().map(GuideUiModelChoice::selection).toList());
        GuideUiModelChoice selected = view.modelChoices().stream()
                .filter(GuideUiModelChoice::selected)
                .findFirst().orElseThrow();
        assertEquals("removed", selected.displayName());
        assertFalse(selected.available());
        assertFalse(view.modelChoices().stream()
                .anyMatch(choice -> choice.selection().equals(
                        GuideModelSelection.client("disabled"))));
    }

    @Test
    void activeRequestKeepsRunningModelWhileSelectorChangesNextRequest() {
        GuideModelSelection running = GuideModelSelection.client("a");
        GuideModelSelection next = GuideModelSelection.client("b");
        GuideRequestSnapshot active = new GuideRequestSnapshot(
                REQUEST,
                "main",
                GuideTopology.CLIENT_LOCAL,
                "question",
                List.of(),
                GuideRequestStatus.MODEL_WAIT,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                running);
        GuideSnapshot snapshot = new GuideSnapshot(
                ACTOR,
                "main",
                GuideModelMode.CLIENT,
                true,
                false,
                GuidePersistenceSnapshot.disabled(),
                List.of(new GuideSessionSnapshot(
                        "main", List.of(), List.of(active), List.of(), next)),
                Instant.EPOCH,
                next,
                List.of(
                        new GuideClientModelProfile(
                                "a", "Model A", true, true, "provider/a", null),
                        new GuideClientModelProfile(
                                "b", "Model B", true, true, "provider/b", null)));

        GuideUiView view = GuideUiView.from(snapshot);

        assertEquals("Model B", view.selectedModel().displayName());
        assertEquals("Model A", view.runningModel().displayName());
        assertTrue(view.modelSwitchPending());
        assertTrue(view.canCancel());
    }

    private static GuideSnapshot snapshot(GuideRequestSnapshot request) {
        return new GuideSnapshot(
                ACTOR, "main", GuideModelMode.CLIENT, true, false,
                List.of(new GuideSessionSnapshot("main", List.<GuideMessage>of(), List.of(request))),
                request.updatedAt());
    }

    private static GuideSnapshot snapshot(
            GuideRequestSnapshot request, GuidePersistenceSnapshot persistence) {
        return new GuideSnapshot(
                ACTOR,
                "main",
                GuideModelMode.CLIENT,
                true,
                false,
                persistence,
                List.of(new GuideSessionSnapshot(
                        "main", List.<GuideMessage>of(), List.of(request))),
                request.updatedAt());
    }

    private static GuideRequestSnapshot request(
            GuideRequestStatus status, String text, Instant terminal, GuideFailure failure) {
        return new GuideRequestSnapshot(
                REQUEST,
                "main",
                GuideTopology.CLIENT_LOCAL,
                "question",
                text.isBlank()
                        ? List.of()
                        : List.of(new GuideTimelineEntry.Assistant(
                                0, text, terminal == null, List.of())),
                status,
                List.of(),
                ModelUsage.empty(),
                null,
                failure,
                Instant.EPOCH, terminal == null ? Instant.EPOCH.plusSeconds(1) : terminal, terminal);
    }
}
