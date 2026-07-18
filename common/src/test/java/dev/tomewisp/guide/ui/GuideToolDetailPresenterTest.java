package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.tomewisp.guide.GuideToolActivity;
import dev.tomewisp.guide.GuideToolStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GuideToolDetailPresenterTest {
    @Test
    void projectsInventoryAndCraftabilityAsPlayerCards() {
        GuideToolDetailView inventory = GuideToolDetailPresenter.project(activity(
                "tomewisp:inspect_inventory", """
                {"status":"success","value":{"counts":{
                  "minecraft:apple":3,"minecraft:iron_ingot":12},
                  "inventory":{"complete":true}}}
                """), false);
        GuideDetailCard.ItemGrid grid = assertInstanceOf(
                GuideDetailCard.ItemGrid.class, inventory.cards().getFirst());
        assertEquals(List.of("minecraft:apple", "minecraft:iron_ingot"),
                grid.items().stream().map(GuideItemView::itemId).toList());
        assertTrue(inventory.debug().isEmpty());

        GuideToolDetailView craftability = GuideToolDetailPresenter.project(activity(
                "tomewisp:calculate_craftability", """
                {"status":"success","value":{"result":{"craftable":false,"conclusive":true,
                  "requestedCrafts":1,"maximumCrafts":0,
                  "allocations":[{"requirementKey":"iron","itemId":"minecraft:iron_ingot","count":4}],
                  "missing":[{"requirementKey":"iron","required":9,"allocated":4,"missing":5,
                    "alternatives":["minecraft:iron_ingot"]}]}}}
                """), false);
        GuideDetailCard.Requirements requirements = assertInstanceOf(
                GuideDetailCard.Requirements.class, craftability.cards().getFirst());
        assertFalse(requirements.craftable());
        assertEquals(4, requirements.requirements().getFirst().allocatedItems().getFirst().count());
        assertEquals(5, requirements.requirements().getFirst().missing());
    }

    @Test
    void normalProjectionExcludesTechnicalDetailsWhileDebugKeepsSeparateCopy() {
        GuideToolActivity activity = activity("tomewisp:search_recipes", """
                {"status":"success","value":{"recipes":[],"catalog":{
                  "completeness":"PARTIAL","recipeCount":0,"semanticGroupCount":0,
                  "providers":[{"sourceId":"viewer:rei","generation":"%s",
                    "state":"UNAVAILABLE","completeness":"UNKNOWN","recipeCount":0,
                    "diagnostics":[{"code":"mod_not_loaded","message":"REI is not installed"}]}]}}}
                """.formatted("0".repeat(64)));

        GuideToolDetailView normal = GuideToolDetailPresenter.project(activity, false);
        String visible = normal.cards() + " " + normal.narration();
        assertTrue(normal.debug().isEmpty());
        assertFalse(visible.contains("PARTIAL"));
        assertFalse(visible.contains("UNAVAILABLE"));
        assertFalse(visible.contains("mod_not_loaded"));
        assertFalse(visible.contains("0".repeat(64)));
        assertFalse(visible.contains("call-secret"));

        GuideToolDetailView debug = GuideToolDetailPresenter.project(activity, true);
        assertEquals("tomewisp:search_recipes", debug.debug().orElseThrow().toolId());
        assertEquals("PARTIAL", debug.debug().orElseThrow().normalized()
                .getAsJsonObject("value").getAsJsonObject("catalog")
                .get("completeness").getAsString());
    }

    @Test
    void failuresUseClosedFriendlyMessagesAndUnknownToolsNeverShowRawJson() {
        GuideToolDetailView failure = GuideToolDetailPresenter.project(activity(
                "tomewisp:get_recipe",
                "{\"status\":\"failure\",\"code\":\"stale_reference\",\"message\":\"generation deadbeef\"}"),
                false);
        GuideDetailCard.Error error = assertInstanceOf(
                GuideDetailCard.Error.class, failure.cards().getFirst());
        assertFalse(error.message().contains("stale_reference"));
        assertFalse(error.message().contains("deadbeef"));

        GuideToolDetailView unknown = GuideToolDetailPresenter.project(activity(
                "tomewisp:future_tool",
                "{\"status\":\"success\",\"value\":{\"secretInternalId\":\"abc\"}}"), false);
        String visible = unknown.cards() + " " + unknown.narration();
        assertFalse(visible.contains("secretInternalId"));
        assertFalse(visible.contains("abc"));
    }

    private static GuideToolActivity activity(String toolId, String json) {
        return new GuideToolActivity(
                "call-secret",
                0,
                toolId,
                GuideToolStatus.SUCCEEDED,
                JsonParser.parseString(json).getAsJsonObject(),
                List.of("保留的友好说明"),
                List.of());
    }
}
