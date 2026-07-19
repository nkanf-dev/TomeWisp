package dev.tomewisp.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.ui.GuideDisplayConfig;
import org.junit.jupiter.api.Test;

final class GeneralSettingsProjectionTest {
    @Test
    void displayControlsDefaultSafelyAndToggleIndependently() {
        GeneralSettingsProjection projection = GeneralSettingsProjection.from(
                GuideDisplayConfig.defaults());

        assertFalse(projection.debugMode());
        assertTrue(projection.animationsEnabled());
        assertTrue(projection.debugStatusKey().contains("disabled"));
        assertTrue(projection.narrationKey().contains("general"));
        assertTrue(projection.toggleDebug().debugMode());
        assertTrue(projection.toggleDebug().animationsEnabled());
        assertFalse(projection.toggleAnimations().animationsEnabled());
        assertFalse(projection.toggleAnimations().debugMode());
    }
}
