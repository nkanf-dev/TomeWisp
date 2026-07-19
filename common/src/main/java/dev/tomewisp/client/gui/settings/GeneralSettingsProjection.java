package dev.tomewisp.client.gui.settings;

import dev.tomewisp.guide.ui.GuideDisplayConfig;
import java.util.Objects;

/** Friendly General-page projection for implemented presentation settings only. */
public record GeneralSettingsProjection(
        boolean debugMode,
        boolean animationsEnabled,
        String titleKey,
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
                display.debugMode(),
                display.animationsEnabled(),
                "screen.tomewisp.settings.general.title",
                "screen.tomewisp.settings.general.debug.label",
                "screen.tomewisp.settings.general.debug.description",
                display.debugMode()
                        ? "screen.tomewisp.settings.general.debug.enabled"
                        : "screen.tomewisp.settings.general.debug.disabled",
                "screen.tomewisp.settings.general.animations.label",
                "screen.tomewisp.settings.general.animations.description",
                display.animationsEnabled()
                        ? "screen.tomewisp.settings.general.animations.enabled"
                        : "screen.tomewisp.settings.general.animations.disabled",
                "screen.tomewisp.settings.general.narration");
    }

    public GuideDisplayConfig toggleDebug() {
        return new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION, !debugMode, animationsEnabled);
    }

    public GuideDisplayConfig toggleAnimations() {
        return new GuideDisplayConfig(
                GuideDisplayConfig.SCHEMA_VERSION, debugMode, !animationsEnabled);
    }
}
