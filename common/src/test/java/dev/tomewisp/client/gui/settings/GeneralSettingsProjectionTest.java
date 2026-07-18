package dev.tomewisp.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.ui.GuideDisplayConfig;
import org.junit.jupiter.api.Test;

final class GeneralSettingsProjectionTest {
    @Test
    void debugModeDefaultsOffAndToggleProducesOnlyDisplayCandidate() {
        GeneralSettingsProjection projection = GeneralSettingsProjection.from(
                GuideDisplayConfig.defaults());

        assertFalse(projection.debugMode());
        assertTrue(projection.debugStatusKey().contains("disabled"));
        assertTrue(projection.narrationKey().contains("debug"));
        assertTrue(projection.toggleDebug().debugMode());
    }
}
