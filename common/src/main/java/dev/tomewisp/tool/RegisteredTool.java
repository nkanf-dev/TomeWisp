package dev.tomewisp.tool;

import java.util.Objects;

/** Immutable registration identity retained across filtered runtime snapshots. */
public record RegisteredTool(String providerId, Tool<?, ?> tool) {
    public RegisteredTool {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider id must not be blank");
        }
        Objects.requireNonNull(tool, "tool");
    }
}
