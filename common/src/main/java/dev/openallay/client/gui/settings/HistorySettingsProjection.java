package dev.openallay.client.gui.settings;

import dev.openallay.settings.SettingsOperation;
import dev.openallay.settings.history.HistorySettingsView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Friendly actor-scoped history page; destructive intents carry no raw identity. */
public record HistorySettingsProjection(
        String titleKey,
        String scopeLabelKey,
        String statusKey,
        String narrationKey,
        List<ActionRow> actions) {
    public enum Action {
        DELETE_CURRENT,
        DELETE_ACTOR,
        RESET_DATABASE
    }

    public HistorySettingsProjection {
        actions = List.copyOf(actions);
    }

    public static HistorySettingsProjection from(
            HistorySettingsView history,
            boolean debugMode,
            SettingsOperation operation) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(operation, "operation");
        boolean idle = operation.kind() == SettingsOperation.Kind.IDLE;
        List<ActionRow> actions = new ArrayList<>();
        actions.add(row(
                Action.DELETE_CURRENT,
                idle && history.currentDeleteAvailable(),
                false));
        actions.add(row(
                Action.DELETE_ACTOR,
                idle && history.actorDeleteAvailable(),
                false));
        if (debugMode) {
            actions.add(row(
                    Action.RESET_DATABASE,
                    idle && history.databaseResetAvailable(),
                    true));
        }
        return new HistorySettingsProjection(
                "screen.openallay.settings.history.title",
                switch (history.connectionKind()) {
                    case NONE -> "screen.openallay.settings.history.scope.none";
                    case SINGLEPLAYER_WORLD -> "screen.openallay.settings.history.scope.world";
                    case MULTIPLAYER_SERVER -> "screen.openallay.settings.history.scope.server";
                },
                "screen.openallay.settings.history.status."
                        + history.health().name().toLowerCase(Locale.ROOT),
                "screen.openallay.settings.history.narration",
                actions);
    }

    private static ActionRow row(Action action, boolean enabled, boolean second) {
        String suffix = switch (action) {
            case DELETE_CURRENT -> "delete_current";
            case DELETE_ACTOR -> "delete_actor";
            case RESET_DATABASE -> "reset_database";
        };
        return new ActionRow(
                action,
                "screen.openallay.settings.history.action." + suffix,
                "screen.openallay.settings.history.action." + suffix + ".description",
                enabled,
                second);
    }

    public record ActionRow(
            Action action,
            String labelKey,
            String descriptionKey,
            boolean enabled,
            boolean requiresSecondConfirmation) {
        public ActionRow {
            Objects.requireNonNull(action, "action");
            if (labelKey == null || labelKey.isBlank()
                    || descriptionKey == null || descriptionKey.isBlank()) {
                throw new IllegalArgumentException("history action localization is required");
            }
        }
    }
}
