package dev.openallay.resource.vfs;

import java.util.Objects;

/** Stable, data-only failure returned for one member of a batch operation. */
public record ResourceOperationFailure(
        String code,
        ResourcePath path,
        String field,
        String message) {
    public ResourceOperationFailure {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        field = field == null || field.isBlank() ? null : field;
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
    }

    public static ResourceOperationFailure from(ResourcePath path, RuntimeException exception) {
        Objects.requireNonNull(exception, "exception");
        if (exception instanceof ResourceOperationException resourceFailure) {
            return new ResourceOperationFailure(resourceFailure.code(), path, null, resourceFailure.getMessage());
        }
        String message = exception.getMessage() == null
                ? "Resource operation failed: " + exception.getClass().getSimpleName()
                : exception.getMessage();
        return new ResourceOperationFailure("invalid_resource_operation", path, null, message);
    }
}
