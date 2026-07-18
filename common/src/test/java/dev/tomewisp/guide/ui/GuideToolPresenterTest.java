package dev.tomewisp.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
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

        var lines = GuideToolPresenter.lines("tomewisp:calculate_craftability", normalized);

        assertEquals("材料还没有备齐。", lines.getFirst());
        assertTrue(lines.stream().anyMatch(value -> value.contains("iron_ingot × 4")));
        assertTrue(lines.stream().anyMatch(value -> value.contains("缺少 iron × 5")));
    }

    @Test
    void failurePresentationNeverExposesInternalCode() {
        var normalized = JsonParser.parseString(
                "{\"status\":\"failure\",\"code\":\"stale_reference\",\"message\":\"reload\"}")
                .getAsJsonObject();
        var lines = GuideToolPresenter.lines("tomewisp:get_recipe", normalized);
        assertEquals("这个结果已经过期，请重新查询。", lines.getFirst());
        assertEquals(1, lines.size());
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

        var lines = GuideToolPresenter.lines("tomewisp:search_recipes", normalized);

        String visible = String.join("\n", lines);
        assertTrue(visible.contains("没有找到匹配的配方"));
        assertTrue(visible.contains("部分配方来源当前不可用"));
        assertTrue(!visible.contains("UNAVAILABLE"));
        assertTrue(!visible.contains("mod_not_loaded"));
        assertTrue(!visible.contains("0".repeat(64)));
    }
}
