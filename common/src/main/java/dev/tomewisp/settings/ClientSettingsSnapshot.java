package dev.tomewisp.settings;

import dev.tomewisp.guide.ui.GuideDisplayConfig;
import dev.tomewisp.settings.model.ModelProfileSettingsView;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.settings.capability.RecipeSettingsView;
import dev.tomewisp.settings.diagnostics.SettingsDiagnosticsSnapshot;
import dev.tomewisp.settings.history.HistorySettingsView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable common projection consumed by the native settings screen. */
public record ClientSettingsSnapshot(
        long generation,
        GuideDisplayConfig display,
        ModelProfileSettingsView models,
        CapabilitySettingsView capabilities,
        RecipeSettingsView recipes,
        HistorySettingsView history,
        SettingsDiagnosticsSnapshot diagnostics,
        SettingsOperation operation,
        SettingsNotice notice) {
    public ClientSettingsSnapshot {
        if (generation < 0) {
            throw new IllegalArgumentException("settings generation must not be negative");
        }
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(operation, "operation");
    }

    public ClientSettingsSnapshot(
            long generation,
            GuideDisplayConfig display,
            ModelProfileSettingsView models,
            CapabilitySettingsView capabilities,
            RecipeSettingsView recipes,
            SettingsOperation operation,
            SettingsNotice notice) {
        this(
                generation,
                display,
                models,
                capabilities,
                recipes,
                HistorySettingsView.disconnected(),
                new SettingsDiagnosticsSnapshot(List.of(), Optional.empty()),
                operation,
                notice);
    }

    public ClientSettingsSnapshot(
            long generation,
            GuideDisplayConfig display,
            ModelProfileSettingsView models,
            SettingsOperation operation,
            SettingsNotice notice) {
        this(
                generation,
                display,
                models,
                CapabilitySettingsView.defaults(),
                RecipeSettingsView.defaults(),
                HistorySettingsView.disconnected(),
                new SettingsDiagnosticsSnapshot(List.of(), Optional.empty()),
                operation,
                notice);
    }
}
