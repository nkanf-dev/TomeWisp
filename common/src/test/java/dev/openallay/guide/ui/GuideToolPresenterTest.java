package dev.openallay.guide.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.openallay.agent.tool.ToolResultDiagnostics;
import dev.openallay.agent.tool.ToolUiReference;
import dev.openallay.agent.tool.ToolUiSummary;
import dev.openallay.guide.GuideToolActivity;
import dev.openallay.guide.GuideToolMessage;
import dev.openallay.guide.GuideToolStatus;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import java.time.Instant;
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

    @Test
    void resourcePresentationReportsBatchOutcomeAndContinuationWithoutTruthDump() {
        var normalized = JsonParser.parseString("""
                {"status":"success","value":{"operation":"resource_query",
                "resultPath":"/result/r2","items":[
                {"inputIndex":0,"input":"/item","status":"success","value":{"rows":2}},
                {"inputIndex":1,"input":"/missing","status":"failure",
                 "failure":{"code":"resource_unavailable","message":"missing"}}]}}
                """).getAsJsonObject();
        GuideToolActivity activity = new GuideToolActivity(
                "call-r2", 0, "openallay:resource_query", GuideToolStatus.SUCCEEDED,
                normalized,
                new ToolUiReference(
                        ResourcePath.parse("/result/r2"),
                        List.of(ResourcePath.parse("/item")),
                        ResourcePresentation.Kind.TABLE,
                        true,
                        new ToolUiSummary("resource_query", 1, 1, List.of("table"))),
                new ToolResultDiagnostics(800, 180, "a".repeat(64), Instant.EPOCH),
                List.of(), List.of());

        var messages = GuideToolPresenter.messages(activity);

        assertEquals(GuideToolMessage.Key.RESOURCE_RESULT_SUMMARY, messages.getFirst().key());
        assertEquals(List.of("resource_query", "1", "1"), messages.getFirst().arguments());
        assertEquals(GuideToolMessage.Key.RESOURCE_RESULT_CONTINUATION, messages.getLast().key());
        assertTrue(messages.toString().length() < normalized.toString().length());
        assertTrue(!messages.toString().contains("resource_unavailable"));
    }
}
