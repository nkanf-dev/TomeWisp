package dev.openallay.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openallay.context.DataCompleteness;
import dev.openallay.context.RecipeEntrySnapshot;
import dev.openallay.context.RecipeReference;
import dev.openallay.testing.GroundedTestFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RecipeProviderSnapshotTest {
    @Test
    void validatesGenerationAndProviderOwnership() {
        RecipeEntrySnapshot recipe = GroundedTestFixtures.ironBlockRecipe();
        RecipeProviderSnapshot snapshot = RecipeProviderSnapshot.available(
                "minecraft:recipe_manager",
                DataCompleteness.COMPLETE,
                List.of(recipe),
                List.of());

        assertEquals(64, snapshot.generation().length());
        assertEquals(recipe.layout(), snapshot.recipes().getFirst().layout());
        assertEquals(recipe.ingredients(), snapshot.recipes().getFirst().ingredients());
        assertThrows(IllegalArgumentException.class, () -> RecipeProviderSnapshot.available(
                "viewer:jei",
                DataCompleteness.COMPLETE,
                List.of(recipe),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RecipeReference(
                "viewer:jei", "ABC", "minecraft:iron_block"));
    }

    @Test
    void unavailableProvidersHaveNoGenerationOrRecords() {
        RecipeProviderSnapshot unavailable = new RecipeProviderSnapshot(
                "viewer:rei",
                null,
                RecipeProviderState.UNAVAILABLE,
                DataCompleteness.UNKNOWN,
                List.of(),
                List.of(new RecipeProviderDiagnostic(
                        "viewer:rei", "mod_not_loaded", "REI is not installed")));

        assertEquals(RecipeProviderState.UNAVAILABLE, unavailable.state());
        assertThrows(IllegalArgumentException.class, () -> new RecipeProviderSnapshot(
                "viewer:rei",
                null,
                RecipeProviderState.FAILED,
                DataCompleteness.PARTIAL,
                List.of(),
                List.of()));
    }
}
