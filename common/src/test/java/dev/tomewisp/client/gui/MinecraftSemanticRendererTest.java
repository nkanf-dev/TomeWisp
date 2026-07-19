package dev.tomewisp.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.guide.semantic.RecipeSemanticHandle;
import dev.tomewisp.guide.semantic.SemanticReference;
import dev.tomewisp.guide.semantic.SemanticReferenceKind;
import dev.tomewisp.guide.semantic.RichComponent;
import org.junit.jupiter.api.Test;

final class MinecraftSemanticRendererTest {
    @Test
    void derivesOnlyTypedAllowlistedIntentsFromValidatedReferences() {
        SemanticReference item = new SemanticReference(
                SemanticReferenceKind.ITEM, "minecraft:iron_ingot", "Iron", false, null);
        RecipeReference recipe = new RecipeReference(
                "viewer:emi", "a".repeat(64), "minecraft:iron_ingot");
        SemanticReference groundedRecipe = new SemanticReference(
                SemanticReferenceKind.RECIPE, RecipeSemanticHandle.encode(recipe),
                "Recipe", true, "tool-1");
        SemanticReference source = new SemanticReference(
                SemanticReferenceKind.SOURCE, "minecraft:recipe_manager",
                "Recipes", true, "tool-1");

        assertEquals(
                new MinecraftSemanticRenderer.Intent.BrowseRecipes("minecraft:iron_ingot"),
                MinecraftSemanticRenderer.intent(item));
        assertEquals(
                new MinecraftSemanticRenderer.Intent.ExactRecipe(recipe),
                MinecraftSemanticRenderer.intent(groundedRecipe));
        assertEquals(
                new MinecraftSemanticRenderer.Intent.Source(
                        "minecraft:recipe_manager", "tool-1"),
                MinecraftSemanticRenderer.intent(source));
        assertNull(MinecraftSemanticRenderer.intent(new SemanticReference(
                SemanticReferenceKind.KEY, "key.jump", "Jump", false, null)));
    }

    @Test
    void malformedRecipeHandleNeverBecomesAnAction() {
        SemanticReference malformed = new SemanticReference(
                SemanticReferenceKind.RECIPE, "not-a-handle", "Recipe", true, "tool-1");
        assertNull(MinecraftSemanticRenderer.intent(malformed));
    }

    @Test
    void animationChangesOnlyTheActiveProgressGlyph() {
        assertEquals("▶", MinecraftSemanticRenderer.progressMarker(
                RichComponent.StepState.ACTIVE, false, 0));
        assertEquals("▶", MinecraftSemanticRenderer.progressMarker(
                RichComponent.StepState.ACTIVE, false, 80));
        assertEquals("▷", MinecraftSemanticRenderer.progressMarker(
                RichComponent.StepState.ACTIVE, true, 0));
        assertEquals("▶", MinecraftSemanticRenderer.progressMarker(
                RichComponent.StepState.ACTIVE, true, 8));
        assertEquals("✓", MinecraftSemanticRenderer.progressMarker(
                RichComponent.StepState.COMPLETE, true, 0));
    }
}
