package dev.tomewisp.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.capability.CapabilityCatalogSnapshot;
import dev.tomewisp.capability.CapabilityChildPage;
import dev.tomewisp.capability.CapabilityKind;
import dev.tomewisp.capability.CapabilityPolicy;
import dev.tomewisp.capability.CapabilitySettingsEntry;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CapabilitySettingsProjectionTest {
    @Test
    void normalCardsHideTechnicalIdsAndRouteRecipesThroughToolCard() {
        CapabilitySettingsProjection projection = CapabilitySettingsProjection.from(
                view(), CapabilityPolicy.defaults(), false);

        assertEquals(List.of(
                CapabilityKind.KNOWLEDGE_SOURCE,
                CapabilityKind.TOOL,
                CapabilityKind.TOOL,
                CapabilityKind.SKILL), projection.cards().stream()
                .map(CapabilitySettingsProjection.Card::kind).toList());
        assertTrue(projection.cards().stream().allMatch(card -> card.debugId() == null));
        assertEquals(
                new CapabilityChildPage("tomewisp:recipe_settings"),
                projection.route("tomewisp:recipes"));
        assertFalse(projection.cards().stream()
                .filter(card -> card.actionId().equals("tomewisp:recipes"))
                .findFirst().orElseThrow().toggleable());
        assertEquals(1, projection.cards(CapabilityKind.KNOWLEDGE_SOURCE).size());
        assertEquals(2, projection.cards(CapabilityKind.TOOL).size());
        assertEquals(1, projection.cards(CapabilityKind.SKILL).size());
    }

    @Test
    void toggleEditsOnlyTheMatchingDenySetAndDebugModeExposesStableId() {
        CapabilitySettingsProjection projection = CapabilitySettingsProjection.from(
                view(), CapabilityPolicy.defaults(), true);

        CapabilityPolicy disabledTool = ((ToolResult.Success<CapabilityPolicy>)
                projection.toggle("test:fact")).value();
        CapabilityPolicy disabledSkill = ((ToolResult.Success<CapabilityPolicy>)
                CapabilitySettingsProjection.from(view(), disabledTool, true)
                        .toggle("fact-guide")).value();

        assertEquals(Set.of("test:fact"), disabledSkill.disabledTools());
        assertEquals(Set.of("fact-guide"), disabledSkill.disabledSkills());
        assertEquals("test:fact", projection.cards().stream()
                .filter(card -> card.actionId().equals("test:fact"))
                .findFirst().orElseThrow().debugId());
        assertNull(projection.route("test:fact"));
    }

    private static CapabilitySettingsView view() {
        return new CapabilitySettingsView(
                CapabilityPolicy.defaults(),
                new CapabilityCatalogSnapshot(List.of(
                        entry("patchouli", CapabilityKind.KNOWLEDGE_SOURCE, null),
                        entry("tomewisp:recipes", CapabilityKind.TOOL,
                                new CapabilityChildPage("tomewisp:recipe_settings")),
                        entry("test:fact", CapabilityKind.TOOL, null),
                        entry("fact-guide", CapabilityKind.SKILL, null))),
                Set.of("future:tool"),
                Set.of());
    }

    private static CapabilitySettingsEntry entry(
            String id, CapabilityKind kind, CapabilityChildPage childPage) {
        String key = id.replace(':', '_').replace('-', '_');
        return new CapabilitySettingsEntry(
                "test:owner",
                id,
                kind,
                "settings.test." + key + ".title",
                "settings.test." + key + ".description",
                childPage,
                true,
                true);
    }
}
