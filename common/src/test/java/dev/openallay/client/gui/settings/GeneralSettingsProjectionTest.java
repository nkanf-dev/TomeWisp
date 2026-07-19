package dev.openallay.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.guide.ui.GuideDisplayConfig;
import org.junit.jupiter.api.Test;

final class GeneralSettingsProjectionTest {
    @Test
    void displayControlsDefaultSafelyAndToggleIndependently() {
        GeneralSettingsProjection projection = GeneralSettingsProjection.from(new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION, false, true, "小羽"));

        assertEquals("小羽", projection.assistantName());
        assertFalse(projection.debugMode());
        assertTrue(projection.animationsEnabled());
        assertTrue(projection.debugStatusKey().contains("disabled"));
        assertTrue(projection.narrationKey().contains("general"));
        assertTrue(projection.toggleDebug().debugMode());
        assertTrue(projection.toggleDebug().animationsEnabled());
        assertEquals("小羽", projection.toggleDebug().assistantName());
        assertFalse(projection.toggleAnimations().animationsEnabled());
        assertFalse(projection.toggleAnimations().debugMode());
        assertEquals("小羽", projection.toggleAnimations().assistantName());
        assertEquals("新名字", projection.renameAssistant(" 新名字 ").assistantName());
    }
}
