package dev.openallay.client.gui.settings;

import java.util.List;

/** Stable top-level native settings navigation. */
public enum SettingsSection {
    GENERAL("screen.openallay.settings.general"),
    MODELS("screen.openallay.settings.models"),
    TOOLS("screen.openallay.settings.tools"),
    SKILLS("screen.openallay.settings.skills"),
    HISTORY("screen.openallay.settings.history"),
    DIAGNOSTICS("screen.openallay.settings.diagnostics"),
    ABOUT("screen.openallay.settings.about");

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
