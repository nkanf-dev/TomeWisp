package dev.tomewisp.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.settings.diagnostics.SettingsDiagnosticCard;
import dev.tomewisp.settings.diagnostics.SettingsDiagnosticsSnapshot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DiagnosticsSettingsProjectionTest {
    @Test
    void friendlyCardsRetainTextStatusAndCannotGainTechnicalSection() {
        SettingsDiagnosticCard card = new SettingsDiagnosticCard(
                SettingsDiagnosticCard.Domain.HISTORY,
                SettingsDiagnosticCard.FriendlyStatus.READY,
                "screen.tomewisp.settings.diagnostics.history.title",
                "screen.tomewisp.settings.diagnostics.status.ready",
                List.of(new SettingsDiagnosticCard.Metric(
                        "screen.tomewisp.settings.diagnostics.metric.pending_writes", 0)));

        DiagnosticsSettingsProjection projection = DiagnosticsSettingsProjection.from(
                new SettingsDiagnosticsSnapshot(List.of(card), Optional.empty()));

        assertEquals(1, projection.cards().size());
        assertEquals("screen.tomewisp.settings.diagnostics.status.ready",
                projection.cards().getFirst().statusKey());
        assertFalse(projection.cards().getFirst().statusTextKey().isBlank());
        assertTrue(projection.debug().isEmpty());
        assertTrue(projection.narrationKey().contains("diagnostics"));
    }
}
