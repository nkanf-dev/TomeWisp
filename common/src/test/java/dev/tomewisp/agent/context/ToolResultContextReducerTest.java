package dev.tomewisp.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRole;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ToolResultContextReducerTest {
    private final ToolResultContextReducer reducer = new ToolResultContextReducer();

    @Test
    void reducesBulkRecipeInventoryAndDocumentDataButKeepsReferencesAndEvidence() {
        JsonObject normalized = JsonParser.parseString("""
                {"status":"success","outputType":"test.SearchRecipesOutput","value":{
                  "recipes":[
                    {"reference":{"sourceId":"viewer:jei","generation":"%s","recipeId":"farmersdelight:apple_cider"},"bulk":"%s"},
                    {"reference":{"sourceId":"viewer:rei","generation":"%s","recipeId":"farmersdelight:apple_cider"},"bulk":"%s"}],
                  "counts":{"minecraft:apple":64,"minecraft:sugar":32},
                  "results":[{"referenceId":"patchouli:test/book/entry","excerpt":"%s"}],
                  "craftable":false,"conclusive":true,"missingCount":2,
                  "evidence":[{"authority":"CLIENT_VISIBLE","completeness":"PARTIAL",
                    "capturedAt":"2026-07-18T00:00:00Z","sourceId":"viewer:jei",
                    "provenance":"JEI public API","gameVersion":"26.2","loader":"fabric","details":{}}]}}
                """.formatted("a".repeat(64), "x".repeat(8_000), "b".repeat(64),
                        "y".repeat(8_000), "z".repeat(8_000))).getAsJsonObject();
        List<ModelMessage> original = exchange(normalized, false);

        ContextProjection projection = reducer.reduce(original, original.size());
        JsonObject reduced = result(projection.messages().get(1)).getAsJsonObject();

        assertEquals(ContextProjection.Kind.TOOL_RESULTS_REDUCED, projection.kind());
        assertEquals("success", reduced.get("status").getAsString());
        assertEquals("test.SearchRecipesOutput", reduced.get("outputType").getAsString());
        assertEquals(2, reduced.getAsJsonArray("references").size());
        assertEquals(1, reduced.getAsJsonArray("evidence").size());
        assertEquals("patchouli:test/book/entry",
                reduced.getAsJsonArray("stableIds").get(0).getAsJsonObject().get("value").getAsString());
        assertFalse(reduced.toString().contains("x".repeat(100)));
        assertFalse(reduced.toString().contains("minecraft:apple"));
        assertTrue(reduced.getAsJsonObject("conclusions").get("craftable").isJsonPrimitive());
        assertTrue(reduced.getAsJsonObject("conclusions").get("conclusive").isJsonPrimitive());
        assertTrue(reduced.getAsJsonObject("conclusions").get("missingCount").isJsonPrimitive());
        assertTrue(reduced.toString().length() < normalized.toString().length() / 5);
    }

    @Test
    void preservesStructuredFailureWithoutInventingSuccess() {
        JsonObject failure = JsonParser.parseString("""
                {"status":"failure","code":"stale_reference","message":"generation changed",
                 "ignored":{"raw":"%s"}}
                """.formatted("secret-body".repeat(100))).getAsJsonObject();

        JsonObject reduced = result(reducer.reduce(exchange(failure, true), 2).messages().get(1))
                .getAsJsonObject();

        assertEquals("failure", reduced.get("status").getAsString());
        assertEquals("stale_reference", reduced.get("code").getAsString());
        assertEquals("generation changed", reduced.get("message").getAsString());
        assertFalse(reduced.has("ignored"));
    }

    @Test
    void malformedHistoricalResultBecomesAnExplicitReducedFailure() {
        ModelMessage use = new ModelMessage(
                ModelRole.ASSISTANT,
                List.of(new ModelContent.ToolUse("call_1", "test__unknown", new JsonObject())));
        ModelMessage malformed = new ModelMessage(
                ModelRole.USER,
                List.of(new ModelContent.ToolResult("call_1", JsonParser.parseString("[1,2,3]"), false)));

        JsonObject reduced = result(reducer.reduce(List.of(use, malformed), 2).messages().get(1))
                .getAsJsonObject();

        assertEquals("failure", reduced.get("status").getAsString());
        assertEquals("context_result_malformed", reduced.get("code").getAsString());
    }

    @Test
    void messagesAtAndAfterProtectedBoundaryRemainExactlyEqual() {
        JsonObject oldResult = JsonParser.parseString(
                "{\"status\":\"success\",\"value\":{\"bulk\":\"" + "x".repeat(500) + "\"}}")
                .getAsJsonObject();
        JsonObject currentResult = JsonParser.parseString(
                "{\"status\":\"success\",\"value\":{\"bulk\":\"" + "y".repeat(500) + "\"}}")
                .getAsJsonObject();
        List<ModelMessage> old = exchange(oldResult, false);
        List<ModelMessage> current = exchange("call_2", currentResult, false);
        List<ModelMessage> all = java.util.stream.Stream.concat(old.stream(), current.stream()).toList();

        ContextProjection projection = reducer.reduce(all, 2);

        assertNotEquals(all.get(1), projection.messages().get(1));
        assertEquals(all.subList(2, 4), projection.messages().subList(2, 4));
        assertEquals(currentResult, result(projection.messages().get(3)));
    }

    private static List<ModelMessage> exchange(JsonObject normalized, boolean error) {
        return exchange("call_1", normalized, error);
    }

    private static List<ModelMessage> exchange(String id, JsonObject normalized, boolean error) {
        return List.of(
                new ModelMessage(
                        ModelRole.ASSISTANT,
                        List.of(new ModelContent.ToolUse(id, "test__tool", new JsonObject()))),
                new ModelMessage(
                        ModelRole.USER,
                        List.of(new ModelContent.ToolResult(id, normalized, error))));
    }

    private static JsonElement result(ModelMessage message) {
        return ((ModelContent.ToolResult) message.content().getFirst()).value();
    }
}
