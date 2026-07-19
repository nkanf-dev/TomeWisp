package dev.openallay.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.settings.diagnostics.SettingsDiagnosticCard;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DiagnosticsSettingsProjectionTest {
    @Test
    void friendlyCardsRetainTextStatusAndCannotGainTechnicalSection() {
        SettingsDiagnosticCard card = new SettingsDiagnosticCard(
                SettingsDiagnosticCard.Domain.HISTORY,
                SettingsDiagnosticCard.FriendlyStatus.READY,
                "screen.openallay.settings.diagnostics.history.title",
                "screen.openallay.settings.diagnostics.status.ready",
                List.of("screen.openallay.settings.diagnostics.history.on_demand"),
                List.of(new SettingsDiagnosticCard.Metric(
                        "screen.openallay.settings.diagnostics.metric.pending_writes", 0)));

        DiagnosticsSettingsProjection projection = DiagnosticsSettingsProjection.from(
                new SettingsDiagnosticsSnapshot(List.of(card), Optional.empty()));

        assertEquals(1, projection.cards().size());
        assertEquals("screen.openallay.settings.diagnostics.status.ready",
                projection.cards().getFirst().statusKey());
        assertFalse(projection.cards().getFirst().statusTextKey().isBlank());
        assertEquals(1, projection.cards().getFirst().noteKeys().size());
        assertTrue(projection.debug().isEmpty());
        assertTrue(projection.narrationKey().contains("diagnostics"));
    }
}
