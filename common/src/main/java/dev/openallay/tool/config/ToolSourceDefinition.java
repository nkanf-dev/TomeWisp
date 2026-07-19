package dev.openallay.tool.config;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable persisted source envelope. Kind-specific fields live only in {@code config}. */
public record ToolSourceDefinition(
        String sourceId,
        String sourceKind,
        String displayName,
        boolean enabled,
        JsonObject config,
        Lifecycle lifecycle) {
    private static final Pattern SOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern SOURCE_KIND = Pattern.compile("[a-z0-9][a-z0-9_.-]*");

    public enum Lifecycle {
        BUILT_IN,
        USER
    }

    public ToolSourceDefinition {
        if (sourceId == null || !SOURCE_ID.matcher(sourceId).matches()) {
            throw new ToolConfigException("invalid_source_id", "Invalid source ID " + sourceId);
        }
        if (sourceKind == null || !SOURCE_KIND.matcher(sourceKind).matches()) {
            throw new ToolConfigException("invalid_source_kind", "Invalid source kind " + sourceKind);
        }
        if (displayName == null || displayName.isBlank()) {
            throw new ToolConfigException("invalid_source_display_name", "Source display name is required");
        }
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Lifecycle derived = lifecycleForId(sourceId);
        if (lifecycle != derived) {
            throw new ToolConfigException(
                    "invalid_source_lifecycle",
                    "Source lifecycle does not match its stable ID namespace");
        }
        config = config.deepCopy();
    }

    @Override
    public JsonObject config() {
        return config.deepCopy();
    }

    public static Lifecycle lifecycleForId(String sourceId) {
        return sourceId != null && sourceId.startsWith("user:") ? Lifecycle.USER : Lifecycle.BUILT_IN;
    }
}
