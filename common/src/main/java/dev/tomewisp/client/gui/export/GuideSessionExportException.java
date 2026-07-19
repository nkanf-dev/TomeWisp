package dev.tomewisp.client.gui.export;

/** Redacted managed-export failure safe for the player-facing adapter. */
public final class GuideSessionExportException extends RuntimeException {
    private final String code;

    public GuideSessionExportException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || !code.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("invalid export failure code");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
