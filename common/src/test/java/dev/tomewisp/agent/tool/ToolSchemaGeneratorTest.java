package dev.tomewisp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ToolSchemaGeneratorTest {
    enum Mode { FAST, COMPLETE }

    record Nested(int count) {}

    record Output(String value, java.time.Instant capturedAt) {}

    record Input(String id, @ToolOptional String kind, Optional<Mode> mode, List<Nested> nested) {}

    @ToolDescription("Choose at least one exact lookup key")
    @ToolAtLeastOne({"id", "kind"})
    record Described(
            @ToolDescription("Exact namespaced identifier")
            @ToolPattern("^[a-z]+:[a-z_]+$") @ToolOptional String id,
            @ToolDescription("Optional kind") @ToolOptional String kind) {}

    @Test
    void createsStrictNestedRecordSchemaAndOptionalFields() {
        JsonObject schema = new ToolSchemaGenerator().generate(Input.class);
        assertEquals("object", schema.get("type").getAsString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertEquals(List.of("id", "nested"), schema.getAsJsonArray("required").asList().stream()
                .map(value -> value.getAsString())
                .toList());
        assertEquals(
                List.of("FAST", "COMPLETE"),
                schema.getAsJsonObject("properties")
                        .getAsJsonObject("mode")
                        .getAsJsonArray("enum")
                        .asList()
                        .stream()
                        .map(value -> value.getAsString())
                        .toList());
    }

    @Test
    void mapsNamespacedNamesAndRejectsUnknownValues() {
        ToolNameCodec names = new ToolNameCodec(List.of("tomewisp:find_recipes"));
        assertEquals("tomewisp__find_recipes", names.encode("tomewisp:find_recipes"));
        assertEquals("tomewisp:find_recipes", names.decode("tomewisp__find_recipes"));
        assertEquals("tomewisp:find_recipes", names.decode("tomewisp:find_recipes"));
        assertThrows(IllegalArgumentException.class, () -> names.decode("unknown"));
        assertThrows(IllegalArgumentException.class, () -> names.decode("other:find_recipes"));
    }

    @Test
    void emitsTrustedDescriptionsPatternsAndAtLeastOneContract() {
        JsonObject schema = new ToolSchemaGenerator().generate(Described.class);
        assertEquals("Choose at least one exact lookup key", schema.get("description").getAsString());
        JsonObject id = schema.getAsJsonObject("properties").getAsJsonObject("id");
        assertEquals("Exact namespaced identifier", id.get("description").getAsString());
        assertEquals("^[a-z]+:[a-z_]+$", id.get("pattern").getAsString());
        assertEquals(2, schema.getAsJsonArray("anyOf").size());
        assertTrue(schema.getAsJsonArray("required").isEmpty());
    }

    @Test
    void rejectsAtLeastOneReferencesToUnknownComponents() {
        @ToolAtLeastOne("missing")
        record Invalid(@ToolOptional String value) {}
        assertThrows(IllegalArgumentException.class,
                () -> new ToolSchemaGenerator().generate(Invalid.class));
    }

    @Test
    void outputSchemaDescribesSerializedShapeWithoutClaimingNullableFieldsAreRequired() {
        JsonObject schema = new ToolSchemaGenerator().generateOutput(Output.class);

        assertEquals("object", schema.get("type").getAsString());
        assertTrue(schema.getAsJsonArray("required").isEmpty());
        assertEquals("date-time", schema.getAsJsonObject("properties")
                .getAsJsonObject("capturedAt").get("format").getAsString());
    }

    @Test
    void everyPlayerFacingBuiltInResultHasACompleteDebugSchema() {
        ToolSchemaGenerator generator = new ToolSchemaGenerator();
        List.of(
                dev.tomewisp.tool.builtin.ResolveResourceTool.Output.class,
                dev.tomewisp.tool.builtin.SearchRecipesTool.Output.class,
                dev.tomewisp.tool.builtin.GetRecipeTool.Output.class,
                dev.tomewisp.tool.builtin.FindItemUsagesTool.Output.class,
                dev.tomewisp.tool.builtin.InspectInventoryTool.Output.class,
                dev.tomewisp.tool.builtin.CalculateCraftabilityTool.Output.class,
                dev.tomewisp.tool.builtin.FindRecipesTool.Output.class,
                dev.tomewisp.tool.builtin.InspectGameStateTool.Output.class,
                dev.tomewisp.tool.builtin.ListKnowledgeSourcesTool.Output.class,
                dev.tomewisp.tool.builtin.SearchKnowledgeTool.Output.class,
                dev.tomewisp.tool.builtin.GetKnowledgeDocumentTool.Output.class,
                dev.tomewisp.tool.builtin.GetPatchouliMultiblockTool.Output.class)
                .forEach(type -> assertEquals(
                        "object", generator.generateOutput(type).get("type").getAsString(),
                        type.getName()));
    }
}
