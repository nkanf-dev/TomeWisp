package dev.tomewisp.client.gui.nativeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideModelSelection;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolStatus;
import dev.tomewisp.guide.semantic.RichComponent;
import dev.tomewisp.guide.semantic.SemanticDocument;
import dev.tomewisp.guide.ui.GuideToolDetailView;
import dev.tomewisp.guide.ui.GuideUiModelChoice;
import dev.tomewisp.guide.ui.GuideUiRow;
import dev.tomewisp.guide.ui.GuideUiView;
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
                "call-exact", 0, "tomewisp:get_recipe", GuideToolStatus.SUCCEEDED,
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
                        "screen.tomewisp.tool.get_recipe", GuideToolStatus.SUCCEEDED,
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
}
