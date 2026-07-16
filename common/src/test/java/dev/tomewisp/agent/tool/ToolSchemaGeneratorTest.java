package dev.tomewisp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ToolSchemaGeneratorTest {
    enum Mode { FAST, COMPLETE }

    record Nested(int count) {}

    record Input(String id, @ToolOptional String kind, Optional<Mode> mode, List<Nested> nested) {}

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
        assertThrows(IllegalArgumentException.class, () -> names.decode("unknown"));
    }
}
