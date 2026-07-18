package dev.tomewisp.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.GuideSource;
import dev.tomewisp.guide.GuideTimelineEntry;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolStatus;
import dev.tomewisp.guide.GuideTopology;
import dev.tomewisp.guide.ui.GuideDisplayConfig;
import dev.tomewisp.guide.ui.GuideUiRow;
import dev.tomewisp.model.ModelUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TomeWispScreenProjectionTest {
    @Test
    void normalSourceLabelIsFriendlyAndDebugLabelIsTechnical() {
        GuideSource source = new GuideSource(
                "tomewisp:inspect_inventory",
                new EvidenceMetadata(
                        DataAuthority.CLIENT_VISIBLE,
                        DataCompleteness.COMPLETE,
                        Instant.EPOCH,
                        "tomewisp:inventory",
                        "tomewisp:captured_inventory",
                        "26.2",
                        "fabric",
                        Map.of()));

        String normal = TomeWispScreen.sourceLabel(source, false);
        assertFalse(normal.contains("CLIENT_VISIBLE"));
        assertFalse(normal.contains("COMPLETE"));
        assertFalse(normal.contains("inventory"));

        String debug = TomeWispScreen.sourceLabel(source, true);
        assertTrue(debug.contains("CLIENT_VISIBLE"));
        assertTrue(debug.contains("COMPLETE"));
        assertTrue(debug.contains("inventory"));
    }

    @Test
    void liveDisplaySupplierReprojectsSameSnapshotWithoutChangingHistory() {
        GuideToolActivity activity = new GuideToolActivity(
                "call-live",
                0,
                "tomewisp:inspect_inventory",
                GuideToolStatus.SUCCEEDED,
                JsonParser.parseString("""
                        {"status":"success","value":{"counts":{"minecraft:apple":3}}}
                        """).getAsJsonObject(),
                List.of("3 apples"),
                List.of());
        GuideRequestSnapshot request = new GuideRequestSnapshot(
                UUID.fromString("d43b1f0c-c527-4284-902e-fab09b799fe0"),
                "main",
                GuideTopology.CLIENT_LOCAL,
                "question",
                List.of(new GuideTimelineEntry.Tool(0, activity)),
                GuideRequestStatus.COMPLETED,
                List.of(),
                ModelUsage.empty(),
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                Instant.EPOCH.plusSeconds(1));
        GuideSnapshot snapshot = new GuideSnapshot(
                UUID.fromString("24475b25-61c3-4bd9-aec3-5eafbe6ae283"),
                "main",
                GuideModelMode.CLIENT,
                true,
                false,
                List.of(new GuideSessionSnapshot("main", List.<GuideMessage>of(), List.of(request))),
                Instant.EPOCH.plusSeconds(1));
        AtomicReference<GuideDisplayConfig> display =
                new AtomicReference<>(GuideDisplayConfig.defaults());

        GuideUiRow.Tool normal = (GuideUiRow.Tool) TomeWispScreen
                .project(snapshot, display::get).rows().get(1);
        display.set(new GuideDisplayConfig(1, true));
        GuideUiRow.Tool debug = (GuideUiRow.Tool) TomeWispScreen
                .project(snapshot, display::get).rows().get(1);

        assertTrue(normal.detail().debug().isEmpty());
        assertTrue(debug.detail().debug().isPresent());
        assertSame(request, snapshot.sessions().getFirst().requests().getFirst());
    }
}
