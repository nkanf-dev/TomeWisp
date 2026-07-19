package dev.tomewisp.guide.history;

public final class GuideHistoryException extends RuntimeException {
    private final String code;

    public GuideHistoryException(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    public GuideHistoryException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        if (code == null || !code.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("history failure code is invalid");
        }
        return code;
    }
}
