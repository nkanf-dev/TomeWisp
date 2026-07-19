package dev.openallay.recipe;

import dev.openallay.context.RecipeReference;
import java.util.List;

public record RecipeCatalogDiagnostic(
        String code, String recipeId, List<RecipeReference> references, String message) {
    public RecipeCatalogDiagnostic {
        if (code == null || !code.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("diagnostic code is invalid");
        }
        recipeId = RecipeReference.requireRecipeId(recipeId);
        references = List.copyOf(references);
        if (references.size() < 2) {
            throw new IllegalArgumentException("catalog conflict requires multiple references");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("diagnostic message is required");
        }
    }
}
