package dev.openallay.settings.history;

import java.util.Objects;

/** Actor-safe history administration state with no actor, scope, path, or SQL identity. */
public record HistorySettingsView(
        ConnectionKind connectionKind,
        Health health,
        int pendingWrites,
        boolean deleting,
        long activeRequests,
        boolean currentDeleteAvailable,
        boolean actorDeleteAvailable,
        boolean databaseResetAvailable) {
    public enum ConnectionKind {
        NONE,
        SINGLEPLAYER_WORLD,
        MULTIPLAYER_SERVER
    }

    public enum Health {
        READY,
        WORKING,
        ATTENTION,
        UNAVAILABLE,
        NOT_CONNECTED
    }

    public HistorySettingsView {
        Objects.requireNonNull(connectionKind, "connectionKind");
        Objects.requireNonNull(health, "health");
        if (pendingWrites < 0 || activeRequests < 0) {
            throw new IllegalArgumentException("history activity must not be negative");
        }
        if (connectionKind == ConnectionKind.NONE
                && (health != Health.NOT_CONNECTED
                        || currentDeleteAvailable
                        || actorDeleteAvailable
                        || databaseResetAvailable)) {
            throw new IllegalArgumentException("disconnected history has no actions");
        }
    }

    public static HistorySettingsView disconnected() {
        return new HistorySettingsView(
                ConnectionKind.NONE,
                Health.NOT_CONNECTED,
                0,
                false,
                0,
                false,
                false,
                false);
    }

    public static HistorySettingsView available(ConnectionKind connectionKind) {
        if (connectionKind == ConnectionKind.NONE) {
            throw new IllegalArgumentException("available history requires a connection");
        }
        return new HistorySettingsView(
                connectionKind,
                Health.READY,
                0,
                false,
                0,
                true,
                true,
                true);
    }
}
