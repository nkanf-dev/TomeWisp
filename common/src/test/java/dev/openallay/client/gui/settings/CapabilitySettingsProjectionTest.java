package dev.openallay.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.capability.CapabilityCatalogSnapshot;
import dev.openallay.capability.CapabilityChildPage;
import dev.openallay.capability.CapabilityKind;
import dev.openallay.capability.CapabilityPolicy;
import dev.openallay.capability.CapabilitySettingsEntry;
import dev.openallay.settings.capability.CapabilitySettingsView;
import dev.openallay.tool.ToolResult;
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
                new CapabilityChildPage("openallay:recipe_settings"),
                projection.route("openallay:recipes"));
        assertFalse(projection.cards().stream()
                .filter(card -> card.actionId().equals("openallay:recipes"))
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
                        entry("openallay:recipes", CapabilityKind.TOOL,
                                new CapabilityChildPage("openallay:recipe_settings")),
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
