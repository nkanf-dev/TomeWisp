package dev.openallay.capability;

import dev.openallay.settings.AtomicSettingsFile;
import dev.openallay.settings.SettingsWriteException;
import dev.openallay.tool.ToolResult;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Objects;

/** Atomically persists and publishes one validated local capability policy. */
public final class CapabilityPolicyStore {
    @FunctionalInterface
    interface FileReplacement {
        void replace(Path target, String contents);
    }

    private final Path path;
    private final FileReplacement files;
    private final CapabilityPolicyLoader loader = new CapabilityPolicyLoader();
    private final CapabilityPolicyWriter writer = new CapabilityPolicyWriter();
    private volatile CapabilityPolicy current = CapabilityPolicy.defaults();

    public CapabilityPolicyStore(Path path) {
        this(path, new AtomicSettingsFile()::replace);
    }

    CapabilityPolicyStore(Path path, FileReplacement files) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.files = Objects.requireNonNull(files, "files");
    }

    public CapabilityPolicy current() {
        return current;
    }

    public ToolResult<CapabilityPolicy> load() {
        ToolResult<CapabilityPolicy> result = loader.load(path);
        if (result instanceof ToolResult.Success<CapabilityPolicy> success) {
            current = success.value();
        }
        return result;
    }

    public ToolResult<CapabilityPolicy> save(CapabilityPolicy candidate) {
        Objects.requireNonNull(candidate, "candidate");
        String encoded;
        CapabilityPolicy validated;
        try {
            encoded = writer.encode(candidate);
            ToolResult<CapabilityPolicy> decoded = loader.load(new StringReader(encoded));
            if (decoded instanceof ToolResult.Failure<CapabilityPolicy> failure) {
                return new ToolResult.Failure<>(failure.code(), failure.message());
            }
            validated = ((ToolResult.Success<CapabilityPolicy>) decoded).value();
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "invalid_capability_config", "Unable to prepare capability settings");
        }

        try {
            files.replace(path, encoded);
        } catch (SettingsWriteException failure) {
            return new ToolResult.Failure<>(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>("settings_write_failed", "Unable to save settings");
        }

        current = validated;
        return new ToolResult.Success<>(validated);
    }
}
