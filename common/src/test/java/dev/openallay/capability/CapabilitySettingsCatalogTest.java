package dev.openallay.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.model.ModelContent;
import dev.openallay.tool.Tool;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CapabilitySettingsCatalogTest {
    @Test
    void catalogsSourcesToolsAndSkillsWithRecipeAsToolChildPage() {
        CapabilitySettingsCatalog catalog = new CapabilitySettingsCatalog();
        catalog.register("openallay:core", List.of(
                descriptor("patchouli", CapabilityKind.KNOWLEDGE_SOURCE, null),
                descriptor(
                        "openallay:recipes",
                        CapabilityKind.TOOL,
                        new CapabilityChildPage("openallay:recipe_settings")),
                descriptor("answer-guide", CapabilityKind.SKILL, null)));

        CapabilityCatalogSnapshot snapshot = catalog.snapshot(new CapabilityCatalogState(
                Set.of("patchouli"), Set.of("answer-guide", "future-skill")));

        assertEquals(
                List.of(
                        CapabilityKind.KNOWLEDGE_SOURCE,
                        CapabilityKind.TOOL,
                        CapabilityKind.SKILL),
                snapshot.entries().stream().map(CapabilitySettingsEntry::kind).toList());
        CapabilitySettingsEntry recipes = entry(snapshot, "openallay:recipes");
        assertEquals(CapabilityKind.TOOL, recipes.kind());
        assertEquals(
                new CapabilityChildPage("openallay:recipe_settings"), recipes.childPage());
        assertFalse(entry(snapshot, "patchouli").available());
        assertTrue(entry(snapshot, "patchouli").enabled());
        assertTrue(entry(snapshot, "openallay:recipes").available());
        assertFalse(entry(snapshot, "answer-guide").enabled());
        assertFalse(snapshot.entries().stream()
                .anyMatch(value -> value.id().equals("top-level:recipes")));
    }

    @Test
    void preservesRegistrationOwnerAndStableOrdering() {
        CapabilitySettingsCatalog catalog = new CapabilitySettingsCatalog();
        catalog.register("addon:zeta", List.of(
                descriptor("zeta:tool", CapabilityKind.TOOL, null)));
        catalog.register("addon:alpha", List.of(
                descriptor("alpha:tool", CapabilityKind.TOOL, null)));

        CapabilityCatalogSnapshot snapshot = catalog.snapshot(CapabilityCatalogState.defaults());

        assertEquals(List.of("alpha:tool", "zeta:tool"), snapshot.entries().stream()
                .map(CapabilitySettingsEntry::id).toList());
        assertEquals("addon:alpha", entry(snapshot, "alpha:tool").ownerId());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.entries().add(snapshot.entries().getFirst()));
    }

    @Test
    void duplicateRegistrationFailsAtomically() {
        CapabilitySettingsCatalog catalog = new CapabilitySettingsCatalog();
        catalog.register("openallay:core", List.of(
                descriptor("alpha:tool", CapabilityKind.TOOL, null)));

        assertThrows(IllegalStateException.class, () -> catalog.register(
                "addon:test",
                List.of(
                        descriptor("beta:tool", CapabilityKind.TOOL, null),
                        descriptor("alpha:tool", CapabilityKind.TOOL, null))));

        CapabilityCatalogSnapshot snapshot = catalog.snapshot(CapabilityCatalogState.defaults());
        assertEquals(List.of("alpha:tool"), snapshot.entries().stream()
                .map(CapabilitySettingsEntry::id).toList());
        assertNull(entry(snapshot, "alpha:tool").childPage());
    }

    @Test
    void descriptorShapeCannotCarryToolsCallbacksUrlsOrPaths() {
        Set<Class<?>> componentTypes = java.util.Arrays.stream(
                        CapabilitySettingsDescriptor.class.getRecordComponents())
                .map(RecordComponent::getType)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(componentTypes.stream().anyMatch(Tool.class::isAssignableFrom));
        assertFalse(componentTypes.stream().anyMatch(java.util.function.Function.class::isAssignableFrom));
        assertFalse(componentTypes.contains(java.net.URI.class));
        assertFalse(componentTypes.contains(java.nio.file.Path.class));
        assertFalse(CapabilitySettingsDescriptor.class.isAssignableFrom(ModelContent.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapabilitySettingsDescriptor(
                        "https://example.invalid",
                        CapabilityKind.TOOL,
                        "settings.valid.title",
                        "settings.valid.description",
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapabilityChildPage("not-namespaced"));
    }

    private static CapabilitySettingsDescriptor descriptor(
            String id, CapabilityKind kind, CapabilityChildPage childPage) {
        String key = id.replace(':', '.').replace('-', '_');
        return new CapabilitySettingsDescriptor(
                id,
                kind,
                "settings.openallay.capability." + key + ".title",
                "settings.openallay.capability." + key + ".description",
                childPage);
    }

    private static CapabilitySettingsEntry entry(
            CapabilityCatalogSnapshot snapshot, String id) {
        return snapshot.entries().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
