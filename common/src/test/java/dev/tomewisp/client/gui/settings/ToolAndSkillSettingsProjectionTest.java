package dev.tomewisp.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.settings.skill.SkillSettingsView;
import dev.tomewisp.settings.tool.ToolSettingsView;
import dev.tomewisp.tool.config.ToolFamilyId;
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
}
