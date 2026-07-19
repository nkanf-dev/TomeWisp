package dev.openallay.recipe;

import dev.openallay.context.DataCompleteness;
import java.util.List;

/** Lightweight tool-facing state for one captured recipe source. */
public record RecipeProviderStatus(
        String sourceId,
        String generation,
        RecipeProviderState state,
        DataCompleteness completeness,
        int recipeCount,
        List<RecipeProviderDiagnostic> diagnostics) {
    public RecipeProviderStatus {
        sourceId = dev.openallay.context.RecipeReference.requireSourceId(sourceId);
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(completeness, "completeness");
        if (state == RecipeProviderState.AVAILABLE) {
            dev.openallay.context.RecipeReference.requireGeneration(generation);
        } else if (generation != null) {
            throw new IllegalArgumentException("unavailable provider status has a generation");
        }
        if (recipeCount < 0) {
            throw new IllegalArgumentException("provider recipe count must not be negative");
        }
        diagnostics = List.copyOf(diagnostics);
    }

    public static RecipeProviderStatus from(RecipeProviderSnapshot snapshot) {
        return new RecipeProviderStatus(
                snapshot.sourceId(),
                snapshot.generation(),
                snapshot.state(),
                snapshot.completeness(),
                snapshot.recipes().size(),
                snapshot.diagnostics());
    }
}
