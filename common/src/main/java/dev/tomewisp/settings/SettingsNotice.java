package dev.tomewisp.settings;

/** Redacted terminal status suitable for native settings presentation. */
public record SettingsNotice(Level level, String code, String message) {
    public enum Level {
        SUCCESS,
        FAILURE
    }

    public SettingsNotice {
        if (level == null || code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("settings notice fields are required");
        }
    }

    public static SettingsNotice success(String code, String message) {
        return new SettingsNotice(Level.SUCCESS, code, message);
    }

    public static SettingsNotice failure(String code, String message) {
        return new SettingsNotice(Level.FAILURE, code, message);
    }
}
