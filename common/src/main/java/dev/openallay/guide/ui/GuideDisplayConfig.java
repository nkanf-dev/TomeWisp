package dev.openallay.guide.ui;

public record GuideDisplayConfig(
        int schemaVersion,
        boolean debugMode,
        boolean animationsEnabled,
        String assistantName) {
    public static final int SCHEMA_VERSION = 3;
    public static final String DEFAULT_ASSISTANT_NAME = "OpenAllay";

    public GuideDisplayConfig {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported display config schema version " + schemaVersion);
        }
        if (assistantName == null) {
            throw new IllegalArgumentException("assistantName must be a string");
        }
        assistantName = assistantName.strip();
        if (assistantName.isEmpty()) {
            throw new IllegalArgumentException("assistantName must not be blank");
        }
        if (assistantName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("assistantName must not contain control characters");
        }
    }

    /** Source-compatible constructor for call sites that accept the product default name. */
    public GuideDisplayConfig(
            int schemaVersion,
            boolean debugMode,
            boolean animationsEnabled) {
        this(schemaVersion, debugMode, animationsEnabled, DEFAULT_ASSISTANT_NAME);
    }

    public static GuideDisplayConfig defaults() {
        return new GuideDisplayConfig(
                SCHEMA_VERSION, false, true, DEFAULT_ASSISTANT_NAME);
    }

    public GuideDisplayConfig withAssistantName(String nextAssistantName) {
        return new GuideDisplayConfig(
                SCHEMA_VERSION, debugMode, animationsEnabled, nextAssistantName);
    }
}
