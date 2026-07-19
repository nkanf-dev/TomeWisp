package dev.openallay.settings;

/** Redacted settings persistence failure safe for player-facing mapping. */
public final class SettingsWriteException extends RuntimeException {
    private final String code;

    public SettingsWriteException() {
        super("Unable to save settings");
        code = "settings_write_failed";
    }

    public String code() {
        return code;
    }
}
