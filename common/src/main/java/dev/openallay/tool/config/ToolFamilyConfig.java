package dev.openallay.tool.config;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One independently persisted and published logical Tool-family candidate. */
public record ToolFamilyConfig(
        int schemaVersion,
        ToolFamilyId toolId,
        boolean enabled,
        List<ToolSourceDefinition> sources) {
    public static final int SCHEMA_VERSION = 1;

    public ToolFamilyConfig {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new ToolConfigException(
                    "unsupported_tool_family_schema",
                    "Unsupported Tool-family schema " + schemaVersion);
        }
        Objects.requireNonNull(toolId, "toolId");
        sources = List.copyOf(sources);
        Set<String> identities = new HashSet<>();
        for (ToolSourceDefinition source : sources) {
            if (!identities.add(source.sourceId())) {
                throw new ToolConfigException(
                        "duplicate_source_id", "Duplicate source ID " + source.sourceId());
            }
        }
    }

    public static ToolFamilyConfig empty(ToolFamilyId family) {
        return new ToolFamilyConfig(SCHEMA_VERSION, family, true, List.of());
    }
}
