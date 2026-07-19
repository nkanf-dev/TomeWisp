package dev.openallay.context;

import java.util.List;
import dev.openallay.recipe.RecipeCatalogDiagnostic;
import dev.openallay.recipe.RecipeProviderSnapshot;
import dev.openallay.recipe.RecipeSemanticGroup;

public record RecipeSnapshot(
        EvidenceMetadata evidence,
        List<RecipeEntrySnapshot> recipes,
        List<RecipeProviderSnapshot> providers,
        List<RecipeSemanticGroup> groups,
        List<RecipeCatalogDiagnostic> diagnostics) {
    public RecipeSnapshot {
        java.util.Objects.requireNonNull(evidence, "evidence");
        recipes = List.copyOf(recipes);
        providers = List.copyOf(providers);
        groups = List.copyOf(groups);
        diagnostics = List.copyOf(diagnostics);
    }

    public RecipeSnapshot(
            EvidenceMetadata evidence,
            List<RecipeEntrySnapshot> recipes,
            List<RecipeProviderSnapshot> providers) {
        this(evidence, recipes, providers, List.of(), List.of());
    }

    public RecipeSnapshot(EvidenceMetadata evidence, List<RecipeEntrySnapshot> recipes) {
        this(evidence, recipes, List.of(), List.of(), List.of());
    }
}
