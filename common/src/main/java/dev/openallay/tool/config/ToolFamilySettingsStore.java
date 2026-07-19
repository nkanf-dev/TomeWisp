package dev.openallay.tool.config;

import dev.openallay.settings.AtomicSettingsFile;
import dev.openallay.settings.SettingsWriteException;
import dev.openallay.tool.ToolResult;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Atomically persists and publishes one independently owned Tool-family candidate. */
public final class ToolFamilySettingsStore {
    @FunctionalInterface
    interface FileReplacement {
        void replace(Path target, String contents) throws Exception;
    }

    private final Path path;
    private final ToolFamilyId family;
    private final ToolSourceKindRegistry registry;
    private final ToolFamilyConfig defaults;
    private final ToolFamilyConfigCodec codec;
    private final FileReplacement files;
    private volatile ToolFamilyConfig current;

    public ToolFamilySettingsStore(
            Path toolsDirectory,
            ToolFamilyId family,
            ToolSourceKindRegistry registry,
            ToolFamilyConfig defaults) {
        this(
                toolsDirectory,
                family,
                registry,
                defaults,
                (target, contents) -> new AtomicSettingsFile().replace(target, contents));
    }

    ToolFamilySettingsStore(
            Path toolsDirectory,
            ToolFamilyId family,
            ToolSourceKindRegistry registry,
            ToolFamilyConfig defaults,
            FileReplacement files) {
        this.family = Objects.requireNonNull(family, "family");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        if (defaults.toolId() != family) {
            throw new IllegalArgumentException("Tool-family defaults belong to another family");
        }
        ToolResult<ToolFamilyConfig> defaultValidation = registry.validate(defaults);
        if (defaultValidation instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
            throw new IllegalArgumentException(failure.message());
        }
        this.codec = new ToolFamilyConfigCodec(registry);
        this.path = Objects.requireNonNull(toolsDirectory, "toolsDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve(family.fileName());
        this.files = Objects.requireNonNull(files, "files");
        this.current = defaults;
    }

    public Path path() {
        return path;
    }

    public ToolFamilyConfig current() {
        return current;
    }

    public ToolFamilyConfig defaults() {
        return defaults;
    }

    public ToolResult<ToolFamilyConfig> load() {
        if (!Files.exists(path)) {
            current = defaults;
            return new ToolResult.Success<>(defaults);
        }
        try (var reader = Files.newBufferedReader(path)) {
            ToolResult<ToolFamilyConfig> decoded = codec.decode(reader);
            if (decoded instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
                return failure;
            }
            ToolFamilyConfig candidate = ((ToolResult.Success<ToolFamilyConfig>) decoded).value();
            ToolResult<ToolFamilyConfig> validation = validateCompleteCandidate(candidate);
            if (validation instanceof ToolResult.Success<ToolFamilyConfig> success) {
                current = success.value();
            }
            return validation;
        } catch (Exception failure) {
            return new ToolResult.Failure<>(
                    "tool_family_read_failed", "Unable to read Tool-family settings");
        }
    }

    public ToolResult<ToolFamilyConfig> save(ToolFamilyConfig candidate) {
        Objects.requireNonNull(candidate, "candidate");
        ToolResult<ToolFamilyConfig> validation = validateCompleteCandidate(candidate);
        if (validation instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
            return failure;
        }

        String encoded;
        ToolFamilyConfig canonical;
        try {
            encoded = codec.encode(candidate);
            ToolResult<ToolFamilyConfig> decoded = codec.decode(new StringReader(encoded));
            if (decoded instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
                return failure;
            }
            canonical = ((ToolResult.Success<ToolFamilyConfig>) decoded).value();
        } catch (ToolConfigException failure) {
            return new ToolResult.Failure<>(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "invalid_tool_family_config", "Unable to prepare Tool-family settings");
        }

        try {
            files.replace(path, encoded);
        } catch (SettingsWriteException failure) {
            return new ToolResult.Failure<>(failure.code(), failure.getMessage());
        } catch (Exception failure) {
            return new ToolResult.Failure<>("settings_write_failed", "Unable to save settings");
        }
        current = canonical;
        return new ToolResult.Success<>(canonical);
    }

    private ToolResult<ToolFamilyConfig> validateCompleteCandidate(ToolFamilyConfig candidate) {
        if (candidate.toolId() != family) {
            return new ToolResult.Failure<>(
                    "tool_family_mismatch", "Candidate belongs to another Tool family");
        }
        ToolResult<ToolFamilyConfig> registered = registry.validate(candidate);
        if (registered instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
            return failure;
        }

        Map<String, ToolSourceDefinition> expectedBuiltIns = builtIns(defaults);
        Map<String, ToolSourceDefinition> candidateBuiltIns = builtIns(candidate);
        for (Map.Entry<String, ToolSourceDefinition> entry : expectedBuiltIns.entrySet()) {
            ToolSourceDefinition actual = candidateBuiltIns.remove(entry.getKey());
            if (actual == null) {
                return new ToolResult.Failure<>(
                        "builtin_source_required",
                        "Built-in source " + entry.getKey() + " cannot be deleted");
            }
            ToolSourceDefinition expected = entry.getValue();
            if (!expected.sourceKind().equals(actual.sourceKind())
                    || !expected.displayName().equals(actual.displayName())
                    || expected.lifecycle() != actual.lifecycle()) {
                return new ToolResult.Failure<>(
                        "builtin_identity_immutable",
                        "Built-in source " + entry.getKey() + " identity cannot be changed");
            }
        }
        if (!candidateBuiltIns.isEmpty()) {
            return new ToolResult.Failure<>(
                    "builtin_source_unregistered", "Candidates cannot create built-in sources");
        }
        return new ToolResult.Success<>(candidate);
    }

    private static Map<String, ToolSourceDefinition> builtIns(ToolFamilyConfig config) {
        Map<String, ToolSourceDefinition> sources = new LinkedHashMap<>();
        for (ToolSourceDefinition source : config.sources()) {
            if (source.lifecycle() == ToolSourceDefinition.Lifecycle.BUILT_IN) {
                sources.put(source.sourceId(), source);
            }
        }
        return sources;
    }
}
