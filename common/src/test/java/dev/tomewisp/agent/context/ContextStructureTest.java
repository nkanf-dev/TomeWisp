package dev.tomewisp.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRole;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ContextStructureTest {
    @Test
    void groupsAssistantToolUsesWithTheFollowingResults() {
        ModelMessage question = ModelMessage.userText("怎么做苹果酒？");
        ModelMessage assistant = assistantWithCalls("call_1", "call_2");
        ModelMessage results = new ModelMessage(
                ModelRole.USER,
                List.of(
                        new ModelContent.ToolResult("call_1", new JsonPrimitive("one"), false),
                        new ModelContent.ToolResult("call_2", new JsonPrimitive("two"), false)));

        List<ContextStructure.Unit> units =
                ContextStructure.units(List.of(question, assistant, results));

        assertEquals(2, units.size());
        assertEquals(0, units.get(0).fromIndex());
        assertEquals(1, units.get(0).toIndexExclusive());
        assertEquals(1, units.get(1).fromIndex());
        assertEquals(3, units.get(1).toIndexExclusive());
        assertTrue(units.get(1).toolExchange());
        ContextStructure.requireBoundary(units, 1, 3);
    }

    @Test
    void rejectsOrphanMissingReorderedAndDuplicateToolResults() {
        ModelMessage orphan = new ModelMessage(
                ModelRole.USER,
                List.of(new ModelContent.ToolResult("call_1", new JsonPrimitive("x"), false)));
        assertThrows(IllegalArgumentException.class, () -> ContextStructure.units(List.of(orphan)));

        ModelMessage assistant = assistantWithCalls("call_1", "call_2");
        ModelMessage missing = new ModelMessage(
                ModelRole.USER,
                List.of(new ModelContent.ToolResult("call_1", new JsonPrimitive("x"), false)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextStructure.units(List.of(assistant, missing)));

        ModelMessage reordered = new ModelMessage(
                ModelRole.USER,
                List.of(
                        new ModelContent.ToolResult("call_2", new JsonPrimitive("x"), false),
                        new ModelContent.ToolResult("call_1", new JsonPrimitive("y"), false)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextStructure.units(List.of(assistant, reordered)));

        ModelMessage duplicateAssistant = assistantWithCalls("call_1", "call_1");
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextStructure.units(List.of(duplicateAssistant, missing)));
    }

    @Test
    void requiresProtectedIndexToStartAtAUnitBoundary() {
        List<ContextStructure.Unit> units = ContextStructure.units(List.of(
                assistantWithCalls("call_1"),
                new ModelMessage(
                        ModelRole.USER,
                        List.of(new ModelContent.ToolResult(
                                "call_1", new JsonPrimitive("result"), false))),
                ModelMessage.userText("next")));

        assertThrows(
                IllegalArgumentException.class,
                () -> ContextStructure.requireBoundary(units, 1, 3));
        ContextStructure.requireBoundary(units, 2, 3);
    }

    @Test
    void summarySourceOmitsReasoningButKeepsVisibleTextAndToolIdentity() {
        JsonObject input = new JsonObject();
        input.addProperty("resource", "minecraft:iron_ingot");
        ModelMessage assistant = new ModelMessage(
                ModelRole.ASSISTANT,
                List.of(
                        new ModelContent.Reasoning("private chain", "signature"),
                        new ModelContent.Text("我会查证。"),
                        new ModelContent.ToolUse("call_1", "tomewisp__resolve_resource", input)));

        List<ModelMessage> safe = ContextStructure.summarySafe(List.of(assistant));

        assertEquals(1, safe.size());
        assertEquals(2, safe.getFirst().content().size());
        assertTrue(safe.getFirst().content().stream().noneMatch(ModelContent.Reasoning.class::isInstance));
    }

    private static ModelMessage assistantWithCalls(String... ids) {
        return new ModelMessage(
                ModelRole.ASSISTANT,
                java.util.Arrays.stream(ids)
                        .map(id -> new ModelContent.ToolUse(id, "test__tool", new JsonObject()))
                        .map(ModelContent.class::cast)
                        .toList());
    }
}
