package dev.openallay.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.openallay.guide.GuideToolMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GuideToolPresenterTest {
    @Test
    void presentsCraftabilityAsAllocationAndMissingMaterials() {
        var normalized = JsonParser.parseString("""
                {"status":"success","value":{"result":{"craftable":false,"conclusive":true,
                "requestedCrafts":1,"maximumCrafts":0,
                "allocations":[{"requirementKey":"iron","itemId":"minecraft:iron_ingot","count":4}],
                "missing":[{"requirementKey":"iron","missing":5}]}}}
                """).getAsJsonObject();

        var messages = GuideToolPresenter.messages("openallay:calculate_craftability", normalized);

        assertEquals(GuideToolMessage.Key.CRAFTABILITY_NOT_READY, messages.getFirst().key());
        assertTrue(messages.stream().anyMatch(message ->
                message.key() == GuideToolMessage.Key.CRAFTABILITY_ALLOCATION
                        && message.arguments().equals(List.of("minecraft:iron_ingot", "4", "iron"))));
        assertTrue(messages.stream().anyMatch(message ->
                message.key() == GuideToolMessage.Key.CRAFTABILITY_MISSING
                        && message.arguments().equals(List.of("iron", "5"))));
    }

    @Test
    void failurePresentationNeverExposesInternalCode() {
        var normalized = JsonParser.parseString(
                "{\"status\":\"failure\",\"code\":\"stale_reference\",\"message\":\"reload\"}")
                .getAsJsonObject();
        var messages = GuideToolPresenter.messages("openallay:get_recipe", normalized);
        assertEquals(List.of(GuideToolMessage.of(
                GuideToolMessage.Key.FAILURE_STALE_REFERENCE)), messages);
    }

    @Test
    void recipePresentationKeepsProviderDiagnosticsOutOfPlayerHistory() {
        var normalized = JsonParser.parseString("""
                {"status":"success","value":{"recipes":[],"catalog":{
                  "completeness":"PARTIAL","recipeCount":0,"semanticGroupCount":0,
                  "providers":[{"sourceId":"viewer:rei","generation":"%s",
                    "state":"UNAVAILABLE","completeness":"UNKNOWN","recipeCount":0,
                    "diagnostics":[{"sourceId":"viewer:rei","code":"mod_not_loaded","message":"REI is not installed"}]}],
                  "conflicts":[]},"evidence":[]}}
                """.formatted("0".repeat(64))).getAsJsonObject();

        var messages = GuideToolPresenter.messages("openallay:search_recipes", normalized);

        assertTrue(messages.stream().anyMatch(message ->
                message.key() == GuideToolMessage.Key.RECIPES_NONE));
        assertTrue(messages.stream().anyMatch(message ->
                message.key() == GuideToolMessage.Key.CATALOG_PARTIAL));
        String visible = messages.toString();
        assertTrue(!visible.contains("UNAVAILABLE"));
        assertTrue(!visible.contains("mod_not_loaded"));
        assertTrue(!visible.contains("0".repeat(64)));
    }
}
