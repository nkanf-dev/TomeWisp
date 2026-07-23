package dev.openallay.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.settings.skill.SkillSettingsView;
import dev.openallay.settings.tool.ToolSettingsView;
import dev.openallay.agent.tool.ToolSchemaGenerator;
import dev.openallay.context.ContextCapability;
import dev.openallay.tool.builtin.CalculateCraftabilityTool;
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
        ToolSettingsView view = withCraftability(ToolSettingsView.empty());

        ToolSettingsProjection normal = ToolSettingsProjection.from(view, false);
        ToolSettingsProjection.ToolCard normalCard = normal.find(ToolFamilyId.CRAFTABILITY)
                .orElseThrow().tools().getFirst();
        assertEquals(List.of("sourceId", "generation", "recipeId", "crafts"),
                normalCard.parameters().stream()
                .map(ToolSettingsProjection.Parameter::name).toList());
        assertTrue(normalCard.parameters().getFirst().required());
        assertTrue(normalCard.readOnly());
        assertTrue(normalCard.debug().isEmpty());
        assertTrue(normalCard.returns().stream()
                .anyMatch(field -> field.name().equals("result")));

        ToolSettingsProjection.ToolCard debug = ToolSettingsProjection.from(view, true)
                .find(ToolFamilyId.CRAFTABILITY).orElseThrow().tools().getFirst();
        assertEquals("openallay:calculate_craftability", debug.debug().orElseThrow().toolId());
        assertEquals("openallay__calculate_craftability", debug.debug().orElseThrow().modelAlias());
        assertEquals("test:provider", debug.debug().orElseThrow().providerId());
        assertTrue(debug.debug().orElseThrow().inputSchema().contains("additionalProperties"));
        assertFalse(debug.debug().orElseThrow().inputSchema().contains("secret-value"));
        assertTrue(debug.debug().orElseThrow().outputSchema().contains("[redacted]"));
        assertFalse(debug.debug().orElseThrow().outputSchema().contains("secret-value"));
        com.google.gson.JsonParser.parseString(debug.debug().orElseThrow().outputSchema())
                .getAsJsonObject();
    }

    private static ToolSettingsView withCraftability(ToolSettingsView empty) {
        ToolSchemaGenerator schemas = new ToolSchemaGenerator();
        com.google.gson.JsonObject outputSchema =
                schemas.generateOutput(CalculateCraftabilityTool.Output.class);
        com.google.gson.JsonObject sensitive = new com.google.gson.JsonObject();
        sensitive.addProperty("type", "string");
        sensitive.addProperty("description", "secret-value");
        outputSchema.getAsJsonObject("properties").add("apiKey", sensitive);
        ToolSettingsView.Member member = new ToolSettingsView.Member(
                "openallay:calculate_craftability",
                "openallay__calculate_craftability",
                "settings.test.craftability.title",
                "settings.test.craftability.description",
                "Allocate inventory against one recipe",
                true,
                true,
                "READ_ONLY",
                Set.of(ContextCapability.PLAYER, ContextCapability.RECIPES),
                "test:provider",
                "screen.openallay.settings.tools.execution.client_bridgeable",
                schemas.generate(CalculateCraftabilityTool.Input.class),
                outputSchema,
                null);
        return new ToolSettingsView(empty.families().stream().map(family ->
                family.id() == ToolFamilyId.CRAFTABILITY
                        ? new ToolSettingsView.Family(
                                family.id(), family.titleKey(), family.descriptionKey(),
                                family.enabled(), true, family.memberToolIds(), List.of(member),
                                family.sources(), family.recipeDetail())
                        : family).toList());
    }
}
