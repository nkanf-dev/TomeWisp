package dev.tomewisp.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SettingsLayoutTest {
    @Test
    void topLevelSectionsUseKnowledgeAndCapabilitiesNotRecipes() {
        assertEquals(List.of(
                        SettingsSection.GENERAL,
                        SettingsSection.MODELS,
                        SettingsSection.KNOWLEDGE_AND_CAPABILITIES,
                        SettingsSection.HISTORY,
                        SettingsSection.DIAGNOSTICS),
                SettingsSection.topLevel());
        assertFalse(SettingsSection.topLevel().stream()
                .anyMatch(section -> section.name().equals("RECIPES")));
    }

    @Test
    void wideLayoutHasRailListAndEditorWithoutOverlap() {
        SettingsLayout layout = SettingsLayout.calculate(960, 600);

        assertTrue(layout.wide());
        assertTrue(layout.navigation().right() <= layout.list().x());
        assertTrue(layout.list().right() <= layout.editor().x());
        assertEquals(layout.content().bottom(), layout.footer().y());
    }

    @Test
    void narrowLayoutUsesOneContentPanelAndBackNavigation() {
        SettingsLayout layout = SettingsLayout.calculate(480, 320);

        assertFalse(layout.wide());
        assertEquals(0, layout.navigation().width());
        assertEquals(0, layout.list().width());
        assertEquals(layout.content(), layout.editor());
        assertTrue(layout.showBack());
    }
}
