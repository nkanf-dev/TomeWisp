package dev.tomewisp.guide.semantic;

import dev.tomewisp.context.RecipeReference;

/** Copyable recipe handle syntax used only by the semantic presentation layer. */
public final class RecipeSemanticHandle {
    private RecipeSemanticHandle() {}

    public static String encode(RecipeReference reference) {
        java.util.Objects.requireNonNull(reference, "reference");
        return reference.sourceId() + "@" + reference.generation() + "/" + reference.recipeId();
    }

    public static RecipeReference decode(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("recipe handle is required");
        }
        int generationMarker = encoded.indexOf('@');
        int recipeMarker = generationMarker < 0 ? -1 : encoded.indexOf('/', generationMarker + 1);
        if (generationMarker <= 0 || recipeMarker != generationMarker + 65
                || recipeMarker == encoded.length() - 1) {
            throw new IllegalArgumentException("recipe handle is malformed");
        }
        return new RecipeReference(
                encoded.substring(0, generationMarker),
                encoded.substring(generationMarker + 1, recipeMarker),
                encoded.substring(recipeMarker + 1));
    }
}
