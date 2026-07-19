package dev.openallay.tool.config;

import dev.openallay.tool.ToolResult;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable source-kind registry that enforces exactly one owning logical Tool. */
public final class ToolSourceKindRegistry {
    private final Map<String, ToolSourceKind> kinds;

    private ToolSourceKindRegistry(Map<String, ToolSourceKind> kinds) {
        this.kinds = Map.copyOf(kinds);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<ToolSourceKind> find(String sourceKind) {
        return Optional.ofNullable(kinds.get(sourceKind));
    }

    public Optional<ToolSourceKind> find(ToolFamilyId owner, String sourceKind) {
        Objects.requireNonNull(owner, "owner");
        return find(sourceKind).filter(kind -> kind.owner() == owner);
    }

    public Collection<ToolSourceKind> kinds() {
        return kinds.values();
    }

    public List<ToolSourceKind> kinds(ToolFamilyId owner) {
        Objects.requireNonNull(owner, "owner");
        return kinds.values().stream()
                .filter(kind -> kind.owner() == owner)
                .sorted(Comparator.comparing(ToolSourceKind::sourceKind))
                .toList();
    }

    public ToolResult<ToolFamilyConfig> validate(ToolFamilyConfig candidate) {
        Objects.requireNonNull(candidate, "candidate");
        try {
            for (ToolSourceDefinition source : candidate.sources()) {
                ToolSourceKind kind = kinds.get(source.sourceKind());
                if (kind == null) {
                    throw new ToolConfigException(
                            "unknown_source_kind", "Unknown source kind " + source.sourceKind());
                }
                if (kind.owner() != candidate.toolId()) {
                    throw new ToolConfigException(
                            "source_owner_mismatch",
                            "Source kind " + source.sourceKind() + " belongs to "
                                    + kind.owner().serializedId());
                }
                if (!kind.lifecycles().contains(source.lifecycle())) {
                    String code = source.lifecycle() == ToolSourceDefinition.Lifecycle.USER
                            ? "source_kind_not_user_creatable"
                            : "source_lifecycle_not_supported";
                    throw new ToolConfigException(
                            code, "Source kind does not support " + source.lifecycle() + " lifecycle");
                }
                if (source.lifecycle() == ToolSourceDefinition.Lifecycle.USER && !kind.userCreatable()) {
                    throw new ToolConfigException(
                            "source_kind_not_user_creatable",
                            "Source kind " + source.sourceKind() + " cannot be created by players");
                }
                kind.validateConfig(source.config());
            }
            return new ToolResult.Success<>(candidate);
        } catch (ToolConfigException failure) {
            return new ToolResult.Failure<>(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "invalid_source_config", "Unable to validate Tool source configuration");
        }
    }

    public static final class Builder {
        private final Map<String, ToolSourceKind> kinds = new LinkedHashMap<>();

        public Builder register(ToolSourceKind kind) {
            Objects.requireNonNull(kind, "kind");
            ToolSourceKind prior = kinds.putIfAbsent(kind.sourceKind(), kind);
            if (prior != null) {
                throw new IllegalArgumentException(
                        "Source kind " + kind.sourceKind() + " is already owned by "
                                + prior.owner().serializedId());
            }
            return this;
        }

        public ToolSourceKindRegistry build() {
            return new ToolSourceKindRegistry(kinds);
        }
    }
}
