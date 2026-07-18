package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class GuideRecipePresenterTest {
    @Test
    void projectsGroundedSearchResultIntoNativeCardReferences() {
        var normalized = JsonParser.parseString("""
                {"status":"success","value":{"recipes":[{
                  "reference":{"sourceId":"minecraft:client_recipe_book","generation":"%s","recipeId":"test:iron"},
                  "references":[
                    {"sourceId":"minecraft:client_recipe_book","generation":"%s","recipeId":"test:iron"},
                    {"sourceId":"viewer:jei","generation":"%s","recipeId":"test:iron"}],
                  "id":"test:iron","type":"minecraft:crafting","workstation":"minecraft:crafting_table",
                  "ingredients":[{"key":"iron","count":9,"consumed":true,"alternatives":[
                    {"kind":"item","id":"minecraft:iron_ingot","resolvedItems":["minecraft:iron_ingot"]}]}],
                  "outputs":[{"stack":{"itemId":"minecraft:iron_block","count":1,"displayName":"Block of Iron"},"chance":1.0}]
                  ,"catalysts":[{"key":"hammer","count":1,"consumed":false,"alternatives":[
                    {"kind":"item","id":"minecraft:hammer","resolvedItems":["minecraft:hammer"]}]}],
                  "byproducts":[{"stack":{"itemId":"minecraft:nugget","count":2,"displayName":"Nugget"},"chance":1.0}],
                  "processing":{"durationTicks":40,"energy":120,"temperature":300.0}
                }]}}
                """.formatted("0".repeat(64), "0".repeat(64), "1".repeat(64)))
                .getAsJsonObject();

        var cards = GuideRecipePresenter.cards("tomewisp:search_recipes", normalized);

        assertEquals(1, cards.size());
        assertEquals("minecraft:iron_block", cards.getFirst().outputs().getFirst().itemId());
        assertEquals(2, cards.getFirst().references().size());
        assertEquals(9, cards.getFirst().ingredients().getFirst().count());
        assertEquals("minecraft:hammer", cards.getFirst().catalysts().getFirst()
                .alternatives().getFirst().id());
        assertEquals(2, cards.getFirst().byproducts().getFirst().count());
        assertEquals(40, cards.getFirst().processing().durationTicks());
        assertTrue(cards.getFirst().references().stream()
                .anyMatch(reference -> reference.sourceId().equals("viewer:jei")));
    }

    @Test
    void malformedSemanticCardFailsClosedToTextFallback() {
        var normalized = JsonParser.parseString("""
                {"status":"success","value":{"recipe":{
                  "reference":{"sourceId":"viewer:jei","generation":"bad","recipeId":"test:iron"},
                  "id":"test:iron","type":"minecraft:crafting",
                  "outputs":[{"stack":{"itemId":"minecraft:iron_block","count":1}}]
                }}}
                """).getAsJsonObject();

        assertTrue(GuideRecipePresenter.cards("tomewisp:get_recipe", normalized).isEmpty());
    }
}
