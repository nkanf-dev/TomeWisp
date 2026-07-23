package dev.openallay.script;

public final class JavascriptExecutionException extends RuntimeException {
    private final String code;

    public JavascriptExecutionException(String code, String message) {
        this(code, message, null);
    }

    public JavascriptExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}

