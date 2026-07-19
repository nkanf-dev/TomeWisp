package dev.openallay.recipe;

import static dev.openallay.recipe.RecipeCatalog.UsageRole.INPUT;
import static dev.openallay.recipe.RecipeCatalog.UsageRole.OUTPUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.openallay.context.RecipeReference;
import dev.openallay.testing.GroundedTestFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RecipeCatalogTest {
    @Test
    void searchesEveryCriterionInStableOrder() {
        RecipeCatalog catalog = new RecipeCatalog(GroundedTestFixtures.recipeSnapshot());

        assertEquals(
                List.of("minecraft:iron_block"),
                catalog.search(new RecipeCatalog.Query(
                                null, "minecraft:iron_block", null, null))
                        .stream()
                        .map(value -> value.reference().recipeId())
                        .toList());
        assertEquals(
                List.of("minecraft:iron_block"),
                catalog.search(new RecipeCatalog.Query(
                                null, null, "minecraft:iron_ingot", "minecraft:crafting"))
                        .stream()
                        .map(value -> value.id())
                        .toList());
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.search(new RecipeCatalog.Query(null, null, null, null)));
    }

    @Test
    void resolvesDetailsAndClassifiesUsageRoles() {
        RecipeCatalog catalog = new RecipeCatalog(GroundedTestFixtures.recipeSnapshot());

        assertEquals(
                "minecraft:iron_block",
                catalog.get(new RecipeReference(
                                "minecraft:recipe_manager",
                                GroundedTestFixtures.RECIPE_GENERATION,
                                "minecraft:iron_block"))
                        .orElseThrow()
                        .id());
        assertEquals(
                List.of(INPUT),
                catalog.usages("minecraft:iron_ingot").stream()
                        .map(RecipeCatalog.Usage::role)
                        .toList());
        assertEquals(
                List.of(OUTPUT),
                catalog.usages("minecraft:iron_block").stream()
                        .map(RecipeCatalog.Usage::role)
                        .toList());
    }
}
