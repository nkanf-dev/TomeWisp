package dev.openallay.context;

import java.util.Objects;
import java.util.UUID;

public record PlayerSnapshot(
        UUID uuid,
        String displayName,
        String dimension,
        BlockPositionSnapshot position,
        String gameMode,
        InventorySnapshot inventory,
        EvidenceMetadata evidence) {
    public PlayerSnapshot {
        Objects.requireNonNull(uuid, "uuid");
        displayName = ContextValidation.nonBlank(displayName, "displayName");
        dimension = ContextValidation.identifier(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        gameMode = ContextValidation.nonBlank(gameMode, "gameMode");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(evidence, "evidence");
    }
}
