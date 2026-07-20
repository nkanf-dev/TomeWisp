package dev.openallay.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRole;
import dev.openallay.resource.projection.ResourceReceipt;
import dev.openallay.resource.vfs.ResourcePath;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ToolResultContextReducerTest {
    private final ToolResultContextReducer reducer = new ToolResultContextReducer();

    @Test
    void historicalBodyBecomesStructuralReceiptWithoutInspectingExactJson() {
        String body = """
                status: success
                resource: /result/r1
                generation: g1
                authority: CLIENT_VISIBLE
                completeness: COMPLETE
                returned: 10/100
                fields: id,name
                  nested_bulk: %s
                """.formatted("x".repeat(8_000));

        ContextProjection projection = reducer.reduce(exchange("call", body, "/result/r1"), 2);
        ModelContent.ToolResult reduced = result(projection.messages().get(1));

        assertEquals(ContextProjection.Kind.TOOL_RESULTS_REDUCED, projection.kind());
        assertTrue(reduced.text().startsWith("historical_result: receipt"));
        assertTrue(reduced.text().contains("resource: /result/r1"));
        assertTrue(reduced.text().contains("authority: CLIENT_VISIBLE"));
        assertTrue(reduced.text().contains("returned: 10/100"));
        assertFalse(reduced.text().contains("nested_bulk"));
        assertFalse(reduced.text().contains("x".repeat(100)));
    }

    @Test
    void failureAndLegacyHistoryRemainExplicitWithoutInventingLiveResources() {
        ModelContent.ToolResult failure = result(reducer.reduce(exchange(
                "failed", "status: failure\ncode: stale_resource\nmessage: generation changed",
                null), 2).messages().get(1));

        assertTrue(failure.text().contains("status: failure"));
        assertTrue(failure.text().contains("code: stale_resource"));
        assertTrue(failure.text().contains("resource: unavailable in restored legacy history"));
        assertTrue(failure.error());
    }

    @Test
    void protectedCurrentExchangeRemainsExactlyEqual() {
        List<ModelMessage> old = exchange("old", "status: success\nvalue: " + "x".repeat(500),
                "/result/old");
        List<ModelMessage> current = exchange("current", "status: success\nvalue: 42",
                "/result/current");
        List<ModelMessage> all = java.util.stream.Stream.concat(old.stream(), current.stream()).toList();

        ContextProjection projection = reducer.reduce(all, 2);

        assertTrue(result(projection.messages().get(1)).text().startsWith("historical_result"));
        assertEquals(all.subList(2, 4), projection.messages().subList(2, 4));
    }

    @Test
    void structuredReceiptsDriveReductionWithoutParsingModelText() {
        ResourceReceipt receipt = new ResourceReceipt(
                ResourcePath.parse("/result/r1"),
                "digest-1",
                "table",
                4,
                40L,
                List.of("id", "damage"),
                "opaque-cursor",
                "client_visible",
                "complete",
                0L,
                4L,
                List.of("minecraft:iron_sword"));
        ModelMessage use = new ModelMessage(ModelRole.ASSISTANT, List.of(
                new ModelContent.ToolUse("call", "resource_query", new com.google.gson.JsonObject())));
        ModelMessage result = new ModelMessage(ModelRole.USER, List.of(
                new ModelContent.ToolResult(
                        "call",
                        "status: failure\ngeneration: malicious-text\n{\"huge\":\"ignored\"}",
                        "/result/r1",
                        List.of(receipt),
                        false)));

        ModelContent.ToolResult reduced = result(reducer.reduce(List.of(use, result), 2)
                .messages().get(1));

        assertTrue(reduced.text().contains("generation: digest-1"));
        assertTrue(reduced.text().contains("returned: 4/40"));
        assertTrue(reduced.text().contains("next_cursor: opaque-cursor"));
        assertFalse(reduced.text().contains("malicious-text"));
        assertEquals(List.of(receipt), reduced.receipts());
    }

    private static List<ModelMessage> exchange(String id, String text, String receiptPath) {
        return List.of(
                new ModelMessage(ModelRole.ASSISTANT, List.of(
                        new ModelContent.ToolUse(id, "resource_read", new com.google.gson.JsonObject()))),
                new ModelMessage(ModelRole.USER, List.of(
                        new ModelContent.ToolResult(id, text, receiptPath, id.equals("failed")))));
    }

    private static ModelContent.ToolResult result(ModelMessage message) {
        return (ModelContent.ToolResult) message.content().getFirst();
    }
}
