package dev.openallay.tool.config;

/** Stable boundary failure for a rejected Tool-family or source candidate. */
public final class ToolConfigException extends IllegalArgumentException {
    private final String code;

    public ToolConfigException(String code, String message) {
        super(message);
        if (code == null || code.isBlank() || message == null || message.isBlank()) {
            throw new IllegalArgumentException("Tool configuration failure code and message are required");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
