package dev.tomewisp.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.ui.GuideDisplayConfig;
import dev.tomewisp.client.gui.settings.SettingsSection;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.settings.ClientSettingsSnapshot;
import dev.tomewisp.settings.SettingsOperation;
import dev.tomewisp.settings.model.ModelProfileSettingsView;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TomeWispSettingsScreenProjectionTest {
    @Test
    void modelProjectionPreservesOrderAndNeverContainsCredentialValue() {
        ModelProfileDefinition alpha = profile("alpha");
        ModelProfileDefinition beta = profile("beta");
        ModelProfilesConfig config = new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION, "alpha", List.of(alpha, beta));
        ModelProfileSettingsView models = ModelProfileSettingsView.from(
                config,
                List.of(
                        new ModelProfileSettingsView.Resolution(alpha, true, 256_000, null),
                        new ModelProfileSettingsView.Resolution(beta, true, 256_000, null)),
                java.util.Set.of("ALPHA_KEY", "BETA_KEY"),
                null,
                null);
        ClientSettingsSnapshot snapshot = new ClientSettingsSnapshot(
                0,
                GuideDisplayConfig.defaults(),
                models,
                SettingsOperation.idle(),
                null);

        TomeWispSettingsScreen.Projection projection =
                TomeWispSettingsScreen.project(snapshot);

        assertEquals(List.of("alpha", "beta"), projection.models().stream()
                .map(TomeWispSettingsScreen.ModelCard::id)
                .toList());
        assertFalse(projection.toString().contains("ALPHA_KEY"));
        assertFalse(projection.toString().contains("secret-value"));
        assertTrue(projection.sections().contains(SettingsSection.TOOLS));
        assertTrue(projection.sections().contains(SettingsSection.SKILLS));
        assertFalse(projection.sections().stream()
                .anyMatch(section -> section.name().equals("RECIPES")));
        assertEquals(0, projection.recipes().sources().size());
        assertEquals(6, projection.tools().families().size());
        assertEquals(0, projection.skills().skills().size());
        assertFalse(projection.general().debugMode());
        assertTrue(projection.general().animationsEnabled());
        assertTrue(projection.history().actions().stream()
                .noneMatch(dev.tomewisp.client.gui.settings.HistorySettingsProjection.ActionRow::enabled));
        assertTrue(projection.diagnostics().debug().isEmpty());
        assertTrue(dev.tomewisp.client.gui.settings.SettingsLayout
                .calculate(960, 600).wide());
        assertTrue(dev.tomewisp.client.gui.settings.SettingsLayout
                .calculate(480, 320).showBack());
    }

    @Test
    void screenshotControlsAreInertUnlessTheExplicitE2ePropertyIsEnabled() {
        String key = dev.tomewisp.guide.e2e.GuideClientE2EConfig.ENABLED;
        String prior = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertFalse(TomeWispSettingsScreen.e2eControlsEnabled());
            System.setProperty(key, "true");
            assertTrue(TomeWispSettingsScreen.e2eControlsEnabled());
        } finally {
            if (prior == null) System.clearProperty(key);
            else System.setProperty(key, prior);
        }
    }

    private static ModelProfileDefinition profile(String id) {
        return new ModelProfileDefinition(
                id,
                id.toUpperCase(),
                true,
                ModelProtocol.OPENAI_CHAT,
                URI.create("https://provider.example/v1"),
                "vendor/" + id,
                id.toUpperCase() + "_KEY",
                256_000,
                4_096,
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                null);
    }
}
