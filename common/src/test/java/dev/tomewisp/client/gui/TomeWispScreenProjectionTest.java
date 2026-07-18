package dev.tomewisp.client.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.guide.GuideSource;
import java.time.Instant;
import java.util.Map;
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
}
