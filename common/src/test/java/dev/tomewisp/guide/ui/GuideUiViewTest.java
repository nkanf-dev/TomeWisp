package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuidePersistenceSnapshot;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideToolActivity;
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

        GuideUiView completed = GuideUiView.from(snapshot(request(
                GuideRequestStatus.COMPLETED, "complete", Instant.EPOCH.plusSeconds(2), null)));
        assertEquals(1, completed.rows().stream().filter(GuideUiRow.Assistant.class::isInstance).count());
        assertEquals("complete", ((GuideUiRow.Assistant) completed.rows().get(1)).text());
        assertFalse(((GuideUiRow.Assistant) completed.rows().get(1)).streaming());
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
                List.of("3 apples"),
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
                snapshot(request), new GuideDisplayConfig(1, true)).rows().get(1);
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
