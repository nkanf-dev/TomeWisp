package dev.openallay.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRole;
import dev.openallay.resource.projection.ToolGroupBudgetAllocator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ContextAssemblerTest {
    @Test
    void budgetsActiveParallelResultsToReceiptsAndCompleteSemanticLines() {
        ContextAssembler assembler = assembler(new ContextBudget(1_600, 200));
        ModelMessage use = new ModelMessage(ModelRole.ASSISTANT, List.of(
                new ModelContent.ToolUse("a", "resource_read", new com.google.gson.JsonObject()),
                new ModelContent.ToolUse("b", "resource_read", new com.google.gson.JsonObject())));
        ModelMessage result = new ModelMessage(ModelRole.USER, List.of(
                new ModelContent.ToolResult("a", large("a"), "/result/a", false),
                new ModelContent.ToolResult("b", large("b"), "/result/b", false)));
        List<ModelMessage> messages = List.of(ModelMessage.userText("question"), use, result);

        ContextAssembler.Assembly assembly = assembler.assemble(
                "system", messages, 0, List.of());

        assertTrue(assembly.fits());
        assertTrue(assembly.projection().estimatedTokens() <= assembly.inputLimitTokens());
        List<ModelContent.ToolResult> projected = assembly.projection().messages().getLast()
                .content().stream().map(ModelContent.ToolResult.class::cast).toList();
        assertTrue(projected.get(0).text().contains("resource: /result/a"));
        assertTrue(projected.get(1).text().contains("resource: /result/b"));
        assertFalse(projected.get(0).text().contains("a-row-199"));
        assertFalse(projected.get(1).text().contains("b-row-199"));
    }

    @Test
    void editsHistoricalToolBodiesButPreservesProtectedCurrentExchange() {
        ContextAssembler assembler = assembler(new ContextBudget(10_000, 200));
        List<ModelMessage> messages = new ArrayList<>();
        messages.addAll(exchange("old", large("old"), "/result/old"));
        messages.addAll(exchange("current", "status: success\nresource: /result/current\nvalue: 42",
                "/result/current"));

        ContextAssembler.Assembly assembly = assembler.assemble("system", messages, 2, List.of());

        assertTrue(assembly.fits());
        ModelContent.ToolResult old = (ModelContent.ToolResult) assembly.projection()
                .messages().get(1).content().getFirst();
        ModelContent.ToolResult current = (ModelContent.ToolResult) assembly.projection()
                .messages().get(3).content().getFirst();
        assertTrue(old.text().startsWith("historical_result: receipt"));
        assertTrue(old.text().contains("resource: /result/old"));
        assertEquals("status: success\nresource: /result/current\nvalue: 42", current.text());
    }

    @Test
    void reportsNonToolHistoryThatStillRequiresConversationCompaction() {
        ContextAssembler assembler = assembler(new ContextBudget(1_000, 100));
        ContextAssembler.Assembly assembly = assembler.assemble(
                "system", List.of(ModelMessage.userText("x".repeat(2_000))), 1, List.of());

        assertFalse(assembly.fits());
        assertTrue(assembly.projection().estimatedTokens() > assembly.inputLimitTokens());
    }

    private static ContextAssembler assembler(ContextBudget budget) {
        return new ContextAssembler(new Utf8ContextTokenEstimator(),
                new ToolResultContextReducer(), new ToolGroupBudgetAllocator(), budget);
    }

    private static List<ModelMessage> exchange(String id, String text, String receiptPath) {
        return List.of(
                new ModelMessage(ModelRole.ASSISTANT, List.of(new ModelContent.ToolUse(
                        id, "resource_read", new com.google.gson.JsonObject()))),
                new ModelMessage(ModelRole.USER, List.of(new ModelContent.ToolResult(
                        id, text, receiptPath, false))));
    }

    private static String large(String prefix) {
        StringBuilder value = new StringBuilder("status: success\ngeneration: g1\nkind: table\n");
        for (int index = 0; index < 200; index++) {
            value.append(prefix).append("-row-").append(index).append(": ")
                    .append("x".repeat(24)).append('\n');
        }
        return value.toString().stripTrailing();
    }
}
