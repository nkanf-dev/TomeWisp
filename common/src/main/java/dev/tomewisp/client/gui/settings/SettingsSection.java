package dev.tomewisp.client.gui.settings;

import java.util.List;

/** Stable top-level native settings navigation. */
public enum SettingsSection {
    GENERAL("screen.tomewisp.settings.general"),
    MODELS("screen.tomewisp.settings.models"),
    TOOLS("screen.tomewisp.settings.tools"),
    SKILLS("screen.tomewisp.settings.skills"),
    HISTORY("screen.tomewisp.settings.history"),
    DIAGNOSTICS("screen.tomewisp.settings.diagnostics");

    private static final List<SettingsSection> TOP_LEVEL = List.of(values());
    private final String translationKey;

    SettingsSection(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    public static List<SettingsSection> topLevel() {
        return TOP_LEVEL;
    }
}
