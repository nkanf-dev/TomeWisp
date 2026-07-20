package dev.openallay.resource.vfs;

import java.util.List;
import java.util.Objects;

/** Exact VFS read. Fields use RFC 6901-style pointers relative to node truth. */
public record ResourceReadRequest(ResourcePath path, List<String> fields) {
    public ResourceReadRequest {
        Objects.requireNonNull(path, "path");
        fields = fields == null ? List.of() : List.copyOf(fields);
        for (String field : fields) {
            if (field == null || field.isBlank() || !field.startsWith("/")) {
                throw new IllegalArgumentException("Read fields must be non-blank absolute field pointers");
            }
        }
    }

    public static ResourceReadRequest full(ResourcePath path) {
        return new ResourceReadRequest(path, List.of());
    }
}
