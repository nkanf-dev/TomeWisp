package dev.openallay.settings;

import dev.openallay.guide.ui.GuideDisplayConfig;
import dev.openallay.settings.model.ModelProfileSettingsView;
import dev.openallay.settings.capability.CapabilitySettingsView;
import dev.openallay.settings.capability.RecipeSettingsView;
import dev.openallay.settings.diagnostics.SettingsDiagnosticsSnapshot;
import dev.openallay.settings.history.HistorySettingsView;
import dev.openallay.settings.skill.SkillSettingsView;
import dev.openallay.settings.tool.ToolSettingsView;
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
        ToolSettingsView tools,
        SkillSettingsView skills,
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
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(skills, "skills");
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
                ToolSettingsView.empty(),
                SkillSettingsView.empty(),
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
                ToolSettingsView.empty(),
                SkillSettingsView.empty(),
                HistorySettingsView.disconnected(),
                new SettingsDiagnosticsSnapshot(List.of(), Optional.empty()),
                operation,
                notice);
    }
}
