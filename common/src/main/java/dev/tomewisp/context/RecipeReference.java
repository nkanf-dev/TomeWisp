package dev.tomewisp.context;

public record RecipeReference(String sourceId, String recipeId) {
    public RecipeReference {
        sourceId = ContextValidation.identifier(sourceId, "sourceId");
        recipeId = ContextValidation.identifier(recipeId, "recipeId");
    }
}
