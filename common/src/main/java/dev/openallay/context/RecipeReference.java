package dev.openallay.context;

public record RecipeReference(String sourceId, String generation, String recipeId) {
    public RecipeReference {
        sourceId = requireSourceId(sourceId);
        generation = requireGeneration(generation);
        recipeId = requireRecipeId(recipeId);
    }

    public static String requireSourceId(String value) {
        return ContextValidation.identifier(value, "sourceId");
    }

    public static String requireRecipeId(String value) {
        return ContextValidation.identifier(value, "recipeId");
    }

    public static String requireGeneration(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("generation must be a lowercase SHA-256 digest");
        }
        return value;
    }
}
