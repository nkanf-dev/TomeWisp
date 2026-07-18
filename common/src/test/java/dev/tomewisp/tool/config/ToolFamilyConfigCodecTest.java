package dev.tomewisp.tool.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import dev.tomewisp.tool.ToolResult;
import java.io.StringReader;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ToolFamilyConfigCodecTest {
    private final ToolSourceKindRegistry registry = ToolSourceKindRegistry.builder()
            .register(ToolSourceKind.localMarkdown())
            .build();
    private final ToolFamilyConfigCodec codec = new ToolFamilyConfigCodec(registry);

    @Test
    void roundTripsTheStrictCommonEnvelopeAndDerivesUserLifecycle() {
        String encoded = """
                {
                  "schemaVersion": 1,
                  "toolId": "tomewisp:guides",
                  "enabled": true,
                  "sources": [{
                    "sourceId": "user:minecraft-notes",
                    "sourceKind": "local_markdown",
                    "displayName": "Minecraft Notes",
                    "enabled": true,
                    "config": {"directory":"minecraft-notes","locale":"zh_cn"}
                  }]
                }
                """;

        ToolFamilyConfig decoded = success(codec.decode(new StringReader(encoded))).value();

        assertEquals(ToolFamilyId.GUIDES, decoded.toolId());
        assertEquals(ToolSourceDefinition.Lifecycle.USER, decoded.sources().getFirst().lifecycle());
        assertEquals(decoded, success(codec.decode(new StringReader(codec.encode(decoded)))).value());
    }

    @Test
    void rejectsUnknownFieldsAtEveryEnvelopeLevel() {
        assertFailure(valid().replace("\"sources\":[", "\"extra\":1,\"sources\":["));
        assertFailure(valid().replace("\"displayName\":\"Notes\",", "\"displayName\":\"Notes\",\"extra\":1,"));
        assertFailure(valid().replace("\"locale\":\"en_us\"", "\"locale\":\"en_us\",\"extra\":1"));
    }

    @Test
    void rejectsKindsOwnedByAnotherToolFamily() {
        assertFailure(valid().replace("tomewisp:guides", "tomewisp:recipes"));
    }

    @Test
    void rejectsDuplicateSourceIds() {
        String source = """
                {"sourceId":"user:notes","sourceKind":"local_markdown","displayName":"Notes",
                 "enabled":true,"config":{"directory":"notes","locale":"en_us"}}
                """;
        assertFailure("""
                {"schemaVersion":1,"toolId":"tomewisp:guides","enabled":true,
                 "sources":[%s,%s]}
                """.formatted(source, source));
    }

    @Test
    void aSourceKindCanBeRegisteredForOnlyOneOwner() {
        ToolSourceKindRegistry.Builder builder = ToolSourceKindRegistry.builder()
                .register(new ToolSourceKind(
                        "shared", ToolFamilyId.GUIDES,
                        Set.of(ToolSourceDefinition.Lifecycle.BUILT_IN), false,
                        List.of(), config -> config));

        assertThrows(IllegalArgumentException.class, () -> builder.register(new ToolSourceKind(
                "shared", ToolFamilyId.RECIPES,
                Set.of(ToolSourceDefinition.Lifecycle.BUILT_IN), false,
                List.of(), config -> config)));
    }

    @Test
    void sourceConfigIsDeeplyDefensiveAtConstructionAndAccess() {
        JsonObject input = new JsonObject();
        input.addProperty("directory", "notes");
        input.addProperty("locale", "en_us");
        ToolSourceDefinition source = new ToolSourceDefinition(
                "user:notes", "local_markdown", "Notes", true, input,
                ToolSourceDefinition.Lifecycle.USER);

        input.addProperty("outside", true);
        JsonObject accessorCopy = source.config();
        accessorCopy.addProperty("alsoOutside", true);

        assertFalse(source.config().has("outside"));
        assertFalse(source.config().has("alsoOutside"));
    }

    @Test
    void everyOrdinaryCallableHasOneLogicalFamilyAndSkillLoadingIsExcluded() {
        assertEquals(
                ToolFamilyId.GAME_CONTEXT,
                ToolFamilyId.forCallableTool("tomewisp:player_context").orElseThrow());
        assertEquals(
                ToolFamilyId.RESOURCE_RESOLUTION,
                ToolFamilyId.forCallableTool("tomewisp:resolve_resource").orElseThrow());
        assertEquals(
                ToolFamilyId.GUIDES,
                ToolFamilyId.forCallableTool("tomewisp:get_patchouli_multiblock").orElseThrow());
        assertFalse(ToolFamilyId.forCallableTool("tomewisp:load_skill").isPresent());
    }

    private void assertFailure(String input) {
        ToolResult.Failure<ToolFamilyConfig> failure = failure(codec.decode(new StringReader(input)));
        assertEquals("invalid_tool_family_config", failure.code());
    }

    private static String valid() {
        return """
                {"schemaVersion":1,"toolId":"tomewisp:guides","enabled":true,"sources":[{
                  "sourceId":"user:notes","sourceKind":"local_markdown","displayName":"Notes",
                  "enabled":true,"config":{"directory":"notes","locale":"en_us"}}]}
                """;
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<ToolFamilyConfig> success(ToolResult<ToolFamilyConfig> result) {
        return (ToolResult.Success<ToolFamilyConfig>) assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<ToolFamilyConfig> failure(ToolResult<ToolFamilyConfig> result) {
        return (ToolResult.Failure<ToolFamilyConfig>) assertInstanceOf(ToolResult.Failure.class, result);
    }
}
