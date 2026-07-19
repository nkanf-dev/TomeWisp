package dev.openallay.client.gui.settings;

import dev.openallay.guide.ui.GuideDisplayConfig;
import java.util.Objects;

/** Friendly General-page projection for implemented presentation settings only. */
public record GeneralSettingsProjection(
        String assistantName,
        boolean debugMode,
        boolean animationsEnabled,
        String titleKey,
        String assistantNameLabelKey,
        String assistantNameDescriptionKey,
        String debugLabelKey,
        String debugDescriptionKey,
        String debugStatusKey,
        String animationsLabelKey,
        String animationsDescriptionKey,
        String animationsStatusKey,
        String narrationKey) {
    public static GeneralSettingsProjection from(GuideDisplayConfig display) {
        Objects.requireNonNull(display, "display");
        return new GeneralSettingsProjection(
                display.assistantName(),
                display.debugMode(),
                display.animationsEnabled(),
                "screen.openallay.settings.general.title",
                "screen.openallay.settings.general.assistant_name.label",
                "screen.openallay.settings.general.assistant_name.description",
                "screen.openallay.settings.general.debug.label",
                "screen.openallay.settings.general.debug.description",
                display.debugMode()
                        ? "screen.openallay.settings.general.debug.enabled"
                        : "screen.openallay.settings.general.debug.disabled",
                "screen.openallay.settings.general.animations.label",
                "screen.openallay.settings.general.animations.description",
                display.animationsEnabled()
                        ? "screen.openallay.settings.general.animations.enabled"
                        : "screen.openallay.settings.general.animations.disabled",
                "screen.openallay.settings.general.narration");
    }

    public GuideDisplayConfig toggleDebug() {
        return new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION,
                !debugMode,
                animationsEnabled,
                assistantName);
    }

    public GuideDisplayConfig toggleAnimations() {
        return new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION,
                debugMode,
                !animationsEnabled,
                assistantName);
    }

    public GuideDisplayConfig renameAssistant(String nextAssistantName) {
        return new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION,
                debugMode,
                animationsEnabled,
                nextAssistantName);
    }
}
