package dev.tomewisp.context;

import java.util.Objects;

public record RecipeOutputSnapshot(ItemStackSnapshot stack, double probability) {
    public RecipeOutputSnapshot {
        Objects.requireNonNull(stack, "stack");
        if (!Double.isFinite(probability) || probability < 0.0D || probability > 1.0D) {
            throw new IllegalArgumentException("output probability must be between zero and one");
        }
    }
}
