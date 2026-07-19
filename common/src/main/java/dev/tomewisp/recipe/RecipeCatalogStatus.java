package dev.tomewisp.recipe;

import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.RecipeSnapshot;
import java.util.List;

/** Deterministic status projection that exposes source health without duplicating recipes. */
public record RecipeCatalogStatus(
        DataCompleteness completeness,
        int recipeCount,
        int semanticGroupCount,
        List<RecipeProviderStatus> providers,
        List<RecipeCatalogDiagnostic> conflicts) {
    public RecipeCatalogStatus {
        java.util.Objects.requireNonNull(completeness, "completeness");
        if (recipeCount < 0 || semanticGroupCount < 0) {
            throw new IllegalArgumentException("recipe catalog counts must not be negative");
        }
        providers = List.copyOf(providers);
        conflicts = List.copyOf(conflicts);
    }

    public static RecipeCatalogStatus from(RecipeSnapshot snapshot) {
        return new RecipeCatalogStatus(
                snapshot.evidence().completeness(),
                snapshot.recipes().size(),
                snapshot.groups().size(),
                snapshot.providers().stream()
                        .map(RecipeProviderStatus::from)
                        .sorted(java.util.Comparator.comparing(RecipeProviderStatus::sourceId))
                        .toList(),
                snapshot.diagnostics());
    }
}
