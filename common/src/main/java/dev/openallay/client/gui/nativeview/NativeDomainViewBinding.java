package dev.openallay.client.gui.nativeview;

import dev.openallay.guide.semantic.RichComponent;
import dev.openallay.guide.ui.GuideRecipeCard;

/** Closed, detached input to a client-thread native view provider. */
public sealed interface NativeDomainViewBinding
        permits NativeDomainViewBinding.Recipe {
    Family family();

    String stableId();

    enum Family {
        RECIPE
    }

    record Recipe(
            String stableId,
            RichComponent.RecipeGrid component,
            GuideRecipeCard recipe) implements NativeDomainViewBinding {
        public Recipe {
            stableId = requireId(stableId);
            java.util.Objects.requireNonNull(component, "component");
            java.util.Objects.requireNonNull(recipe, "recipe");
            if (!recipe.references().contains(component.recipe())) {
                throw new IllegalArgumentException("native recipe binding reference is not exact");
            }
        }

        @Override
        public Family family() {
            return Family.RECIPE;
        }
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("native view stable ID is required");
        }
        return value;
    }
}
