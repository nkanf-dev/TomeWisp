package dev.openallay.context;

import java.util.Objects;
import java.util.UUID;

public record CallerSnapshot(
        CallerKind kind, UUID uuid, String displayName, boolean gameMaster) {
    public CallerSnapshot {
        Objects.requireNonNull(kind, "kind");
        displayName = ContextValidation.nonBlank(displayName, "displayName");
        if (kind == CallerKind.PLAYER && uuid == null) {
            throw new IllegalArgumentException("Player caller requires a UUID");
        }
        if (kind == CallerKind.CONSOLE && uuid != null) {
            throw new IllegalArgumentException("Console caller must not have a UUID");
        }
    }
}
