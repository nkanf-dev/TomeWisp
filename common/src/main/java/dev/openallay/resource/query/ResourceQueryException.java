package dev.openallay.resource.query;

public final class ResourceQueryException extends RuntimeException {
    private final String code;
    private final String field;

    public ResourceQueryException(String code, String message) {
        this(code, null, message);
    }

    public ResourceQueryException(String code, String field, String message) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        this.code = code;
        this.field = field == null || field.isBlank() ? null : field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
