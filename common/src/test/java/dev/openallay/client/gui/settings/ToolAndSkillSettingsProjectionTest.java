package dev.openallay.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.settings.skill.SkillSettingsView;
import dev.openallay.settings.tool.ToolSettingsView;
import dev.openallay.agent.tool.ToolSchemaGenerator;
import dev.openallay.context.ContextCapability;
import dev.openallay.tool.builtin.ResolveResourceTool;
import dev.openallay.tool.config.ToolFamilyId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ToolAndSkillSettingsProjectionTest {
    @Test
    void toolsAreSixSelectableFamiliesAndSelectionDoesNotToggle() {
        ToolSettingsProjection projection =
                ToolSettingsProjection.from(ToolSettingsView.empty(), false);

        assertEquals(6, projection.families().size());
        assertTrue(projection.find(ToolFamilyId.RECIPES).orElseThrow().enabled());
        assertFalse(projection.toggleTool(ToolFamilyId.RECIPES).enabled());
        assertTrue(projection.find(ToolFamilyId.RECIPES).orElseThrow().enabled());
    }

    @Test
    void emptySkillCatalogHasNoSyntheticOptionsOrToggleRows() {
        SkillSettingsProjection projection =
                SkillSettingsProjection.from(SkillSettingsView.empty(), false);

        assertTrue(projection.skills().isEmpty());
        assertEquals(0, projection.diagnosticCount());
    }

    @Test
    void normalToolCardsAreFriendlyAndDebugAddsOnlyRedactedProtocolMetadata() {
        ToolSettingsView view = withResolver(ToolSettingsView.empty());

        ToolSettingsProjection normal = ToolSettingsProjection.from(view, false);
        ToolSettingsProjection.ToolCard normalCard = normal.find(ToolFamilyId.RESOURCE_RESOLUTION)
                .orElseThrow().tools().getFirst();
        assertEquals(List.of("query", "kind", "dataset", "namespace", "describe", "pipeline"),
                normalCard.parameters().stream()
                .map(ToolSettingsProjection.Parameter::name).toList());
        assertFalse(normalCard.parameters().getFirst().required());
        assertFalse(normalCard.parameters().get(1).required());
        assertTrue(normalCard.readOnly());
        assertTrue(normalCard.debug().isEmpty());
        assertTrue(normalCard.returns().stream()
                .anyMatch(field -> field.name().equals("matches")));

        ToolSettingsProjection.ToolCard debug = ToolSettingsProjection.from(view, true)
                .find(ToolFamilyId.RESOURCE_RESOLUTION).orElseThrow().tools().getFirst();
        assertEquals("openallay:resolve_resource", debug.debug().orElseThrow().toolId());
        assertEquals("openallay__resolve_resource", debug.debug().orElseThrow().modelAlias());
        assertEquals("test:provider", debug.debug().orElseThrow().providerId());
        assertTrue(debug.debug().orElseThrow().inputSchema().contains("additionalProperties"));
        assertFalse(debug.debug().orElseThrow().inputSchema().contains("secret-value"));
        assertTrue(debug.debug().orElseThrow().outputSchema().contains("[redacted]"));
        assertFalse(debug.debug().orElseThrow().outputSchema().contains("secret-value"));
        com.google.gson.JsonParser.parseString(debug.debug().orElseThrow().outputSchema())
                .getAsJsonObject();
    }

    private static ToolSettingsView withResolver(ToolSettingsView empty) {
        ToolSchemaGenerator schemas = new ToolSchemaGenerator();
        com.google.gson.JsonObject outputSchema = schemas.generateOutput(ResolveResourceTool.Output.class);
        com.google.gson.JsonObject sensitive = new com.google.gson.JsonObject();
        sensitive.addProperty("type", "string");
        sensitive.addProperty("description", "secret-value");
        outputSchema.getAsJsonObject("properties").add("apiKey", sensitive);
        ToolSettingsView.Member member = new ToolSettingsView.Member(
                "openallay:resolve_resource",
                "openallay__resolve_resource",
                "settings.test.resolve.title",
                "settings.test.resolve.description",
                "Resolve resources",
                true,
                true,
                "READ_ONLY",
                Set.of(ContextCapability.REGISTRIES),
                "test:provider",
                "screen.openallay.settings.tools.execution.client_bridgeable",
                schemas.generate(ResolveResourceTool.Input.class),
                outputSchema,
                null);
        return new ToolSettingsView(empty.families().stream().map(family ->
                family.id() == ToolFamilyId.RESOURCE_RESOLUTION
                        ? new ToolSettingsView.Family(
                                family.id(), family.titleKey(), family.descriptionKey(),
                                family.enabled(), true, family.memberToolIds(), List.of(member),
                                family.sources(), family.recipeDetail())
                        : family).toList());
    }
}
