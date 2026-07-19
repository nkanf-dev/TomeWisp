package dev.openallay.context;

import java.util.Objects;

public record InventorySlotSnapshot(int slot, ItemStackSnapshot stack) {
    public InventorySlotSnapshot {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must not be negative");
        }
        Objects.requireNonNull(stack, "stack");
    }
}
