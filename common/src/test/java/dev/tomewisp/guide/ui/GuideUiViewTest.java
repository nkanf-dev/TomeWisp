package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.guide.GuideTimelineEntry;
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

    private static GuideSnapshot snapshot(GuideRequestSnapshot request) {
        return new GuideSnapshot(
                ACTOR, "main", GuideModelMode.CLIENT, true, false,
                List.of(new GuideSessionSnapshot("main", List.<GuideMessage>of(), List.of(request))),
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
