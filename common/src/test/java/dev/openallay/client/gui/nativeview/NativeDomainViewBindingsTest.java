package dev.openallay.client.gui.nativeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.openallay.context.RecipeReference;
import dev.openallay.guide.GuideModelMode;
import dev.openallay.guide.GuideModelSelection;
import dev.openallay.guide.GuideToolActivity;
import dev.openallay.guide.GuideToolStatus;
import dev.openallay.guide.semantic.RichComponent;
import dev.openallay.guide.semantic.SemanticDocument;
import dev.openallay.guide.ui.GuideToolDetailView;
import dev.openallay.guide.ui.GuideUiModelChoice;
import dev.openallay.guide.ui.GuideUiRow;
import dev.openallay.guide.ui.GuideUiView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NativeDomainViewBindingsTest {
    @Test
    void bindsOnlyTheSameRequestAndOriginInvocationExactRecipe() {
        UUID requestId = UUID.fromString("116157da-2915-4705-a1fb-5106d31b8afb");
        String generation = "a".repeat(64);
        RecipeReference reference = new RecipeReference(
                "minecraft:recipe_manager", generation, "minecraft:apple");
        GuideToolActivity activity = new GuideToolActivity(
                "call-exact", 0, "openallay:get_recipe", GuideToolStatus.SUCCEEDED,
                JsonParser.parseString("""
                        {"status":"success","value":{"recipe":{
                          "reference":{"sourceId":"minecraft:recipe_manager","generation":"%s","recipeId":"minecraft:apple"},
                          "references":[],"id":"minecraft:apple","type":"minecraft:crafting",
                          "workstation":"minecraft:crafting_table",
                          "outputs":[{"stack":{"itemId":"minecraft:apple","count":1,"displayName":"Apple"}}],
                          "ingredients":[],"catalysts":[],"byproducts":[]}}}
                        """.formatted(generation)).getAsJsonObject(),
                List.of(), List.of());
        GuideUiRow.Tool tool = new GuideUiRow.Tool(
                requestId, 0, activity,
                new GuideToolDetailView(
                        "screen.openallay.tool.get_recipe", GuideToolStatus.SUCCEEDED,
                        List.of(), List.of(), Optional.empty()));
        GuideUiRow.Assistant assistant = new GuideUiRow.Assistant(
                requestId, 1, "answer", SemanticDocument.empty(), false, List.of());
        GuideUiModelChoice model = new GuideUiModelChoice(
                GuideModelSelection.client("profile-1"), "Profile", true, true, false);
        GuideUiView view = new GuideUiView(
                "main", GuideModelMode.CLIENT, true, false, true, false, false,
                null, List.of(), List.of(tool, assistant), List.of(model), "ready");
        RichComponent.RecipeGrid component = new RichComponent.RecipeGrid(
                "c".repeat(64), reference, "call-exact", "Apple", "Apple recipe", "Apple recipe");

        NativeDomainViewBinding.Recipe binding = NativeDomainViewBindings.recipe(
                view, assistant, component).orElseThrow();
        assertEquals(reference, binding.recipe().reference());

        RichComponent.RecipeGrid wrongOrigin = new RichComponent.RecipeGrid(
                "d".repeat(64), reference, "call-other", "", "fallback", "narration");
        assertTrue(NativeDomainViewBindings.recipe(view, assistant, wrongOrigin).isEmpty());
    }

    @Test
    void createsStableNativeBindingDirectlyFromValidatedRecipeCard() {
        String generation = "b".repeat(64);
        RecipeReference reference = new RecipeReference(
                "minecraft:recipe_manager", generation, "minecraft:apple");
        dev.openallay.guide.ui.GuideRecipeCard card = new dev.openallay.guide.ui.GuideRecipeCard(
                reference,
                List.of(reference),
                "minecraft:apple",
                "minecraft:crafting",
                "minecraft:crafting_table",
                List.of(new dev.openallay.guide.ui.GuideRecipeCard.Output(
                        "minecraft:apple", 1, "Apple")));

        NativeDomainViewBinding.Recipe first = NativeDomainViewBindings.recipe(
                "tool:request:call:recipe", "call-vfs", card);
        NativeDomainViewBinding.Recipe second = NativeDomainViewBindings.recipe(
                "tool:request:call:recipe", "call-vfs", card);

        assertEquals(first, second);
        assertEquals("call-vfs", first.component().originInvocationId());
        assertEquals(64, first.component().nodeId().length());
    }
}
