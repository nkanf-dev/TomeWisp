package dev.tomewisp.guide;

public record GuideNotice(Level level, String message) {
    public enum Level { INFO, ERROR }

    public GuideNotice {
        java.util.Objects.requireNonNull(level, "level");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("notice message must not be blank");
        }
    }

    public static GuideNotice info(String message) {
        return new GuideNotice(Level.INFO, message);
    }

    public static GuideNotice error(String message) {
        return new GuideNotice(Level.ERROR, message);
    }
}
