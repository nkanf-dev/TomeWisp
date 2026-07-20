package dev.openallay.resource.vfs;

public final class ResourceOperationException extends RuntimeException {
    private final String code;

    public ResourceOperationException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
