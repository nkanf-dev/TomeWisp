package dev.openallay.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.settings.SettingsOperation;
import dev.openallay.settings.history.HistorySettingsView;
import java.util.List;
import org.junit.jupiter.api.Test;

final class HistorySettingsProjectionTest {
    @Test
    void normalModeUsesDistinctActorScopedLabelsAndHidesDatabaseReset() {
        HistorySettingsProjection projection = HistorySettingsProjection.from(
                HistorySettingsView.available(
                        HistorySettingsView.ConnectionKind.MULTIPLAYER_SERVER),
                false,
                SettingsOperation.idle());

        assertEquals("screen.openallay.settings.history.scope.server",
                projection.scopeLabelKey());
        assertEquals(
                List.of(
                        HistorySettingsProjection.Action.DELETE_CURRENT,
                        HistorySettingsProjection.Action.DELETE_ACTOR),
                projection.actions().stream()
                        .map(HistorySettingsProjection.ActionRow::action)
                        .toList());
        assertFalse(projection.actions().getFirst().labelKey()
                .equals(projection.actions().getLast().labelKey()));
        assertTrue(projection.actions().stream()
                .allMatch(HistorySettingsProjection.ActionRow::enabled));
        assertTrue(projection.narrationKey().contains("history"));
    }

    @Test
    void debugModeAddsDistinctSecondStageResetAndBusyStateDisablesEveryAction() {
        HistorySettingsView busy = new HistorySettingsView(
                HistorySettingsView.ConnectionKind.SINGLEPLAYER_WORLD,
                HistorySettingsView.Health.WORKING,
                1,
                false,
                0,
                false,
                false,
                false);

        HistorySettingsProjection projection = HistorySettingsProjection.from(
                busy, true, SettingsOperation.idle());

        HistorySettingsProjection.ActionRow reset = projection.actions().stream()
                .filter(row -> row.action() == HistorySettingsProjection.Action.RESET_DATABASE)
                .findFirst().orElseThrow();
        assertTrue(reset.requiresSecondConfirmation());
        assertTrue(projection.actions().stream()
                .noneMatch(HistorySettingsProjection.ActionRow::enabled));
        assertTrue(projection.statusKey().contains("working"));
    }
}
