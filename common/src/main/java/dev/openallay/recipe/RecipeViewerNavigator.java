package dev.openallay.recipe;

import dev.openallay.context.RecipeReference;

public interface RecipeViewerNavigator {
    String viewerId();

    boolean supportsExactRecipe();

    RecipeNavigationResult openRecipes(String itemId);

    RecipeNavigationResult openUsages(String itemId);

    RecipeNavigationResult openExact(RecipeReference reference);
}
