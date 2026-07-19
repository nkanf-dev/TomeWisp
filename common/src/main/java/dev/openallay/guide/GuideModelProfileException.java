package dev.openallay.guide;

/** Structured local dispatch failure used when a captured profile cannot run. */
public final class GuideModelProfileException extends RuntimeException {
    private final String code;

    public GuideModelProfileException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
