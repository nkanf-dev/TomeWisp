package dev.openallay.recipe;

import dev.openallay.context.DataCompleteness;
import dev.openallay.context.RecipeEntrySnapshot;
import dev.openallay.context.RecipeReference;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record RecipeProviderSnapshot(
        String sourceId,
        String generation,
        RecipeProviderState state,
        DataCompleteness completeness,
        List<RecipeEntrySnapshot> recipes,
        List<RecipeProviderDiagnostic> diagnostics) {
    public RecipeProviderSnapshot {
        sourceId = RecipeReference.requireSourceId(sourceId);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(completeness, "completeness");
        recipes = List.copyOf(recipes);
        diagnostics = List.copyOf(diagnostics);
        if (state == RecipeProviderState.AVAILABLE) {
            RecipeReference.requireGeneration(generation);
        } else if (generation != null) {
            throw new IllegalArgumentException("unavailable provider must not expose a generation");
        }
        if (state != RecipeProviderState.AVAILABLE && !recipes.isEmpty()) {
            throw new IllegalArgumentException("unavailable provider must not expose recipes");
        }
        if (state != RecipeProviderState.AVAILABLE && completeness != DataCompleteness.UNKNOWN) {
            throw new IllegalArgumentException("unavailable provider completeness must be unknown");
        }
        HashSet<String> recipeIds = new HashSet<>();
        for (RecipeEntrySnapshot recipe : recipes) {
            if (!recipe.reference().sourceId().equals(sourceId)
                    || !recipe.reference().generation().equals(generation)) {
                throw new IllegalArgumentException("recipe belongs to another provider generation");
            }
            if (!recipeIds.add(recipe.reference().recipeId())) {
                throw new IllegalArgumentException("provider contains duplicate recipe reference");
            }
        }
        for (RecipeProviderDiagnostic diagnostic : diagnostics) {
            if (!diagnostic.sourceId().equals(sourceId)) {
                throw new IllegalArgumentException("diagnostic belongs to another provider");
            }
        }
        if (state == RecipeProviderState.AVAILABLE
                && !RecipeCanonicalizer.providerGeneration(sourceId, recipes).equals(generation)) {
            throw new IllegalArgumentException("provider generation does not match its records");
        }
    }

    public static RecipeProviderSnapshot available(
            String sourceId,
            DataCompleteness completeness,
            List<RecipeEntrySnapshot> recipes,
            List<RecipeProviderDiagnostic> diagnostics) {
        sourceId = RecipeReference.requireSourceId(sourceId);
        List<RecipeEntrySnapshot> detached = List.copyOf(recipes);
        for (RecipeEntrySnapshot recipe : detached) {
            if (!recipe.reference().sourceId().equals(sourceId)) {
                throw new IllegalArgumentException("recipe belongs to another provider");
            }
        }
        String generation = RecipeCanonicalizer.providerGeneration(sourceId, detached);
        String finalSourceId = sourceId;
        List<RecipeEntrySnapshot> rebound = detached.stream()
                .map(recipe -> recipe.withReference(new RecipeReference(
                        finalSourceId, generation, recipe.reference().recipeId())))
                .toList();
        return new RecipeProviderSnapshot(
                sourceId,
                generation,
                RecipeProviderState.AVAILABLE,
                completeness,
                rebound,
                diagnostics);
    }

    public static RecipeProviderSnapshot unavailable(String sourceId, String code, String message) {
        return inactive(sourceId, RecipeProviderState.UNAVAILABLE, code, message);
    }

    public static RecipeProviderSnapshot failed(String sourceId, String code, String message) {
        return inactive(sourceId, RecipeProviderState.FAILED, code, message);
    }

    private static RecipeProviderSnapshot inactive(
            String sourceId, RecipeProviderState state, String code, String message) {
        return new RecipeProviderSnapshot(
                sourceId,
                null,
                state,
                DataCompleteness.UNKNOWN,
                List.of(),
                List.of(new RecipeProviderDiagnostic(sourceId, code, message)));
    }
}
