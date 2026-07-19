package dev.tomewisp.guide.ui;

import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.settings.AtomicSettingsFile;
import dev.tomewisp.settings.SettingsWriteException;
import dev.tomewisp.tool.ToolResult;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Objects;

/** Atomically persists and publishes the last valid local Guide display config. */
public final class GuideDisplayRuntime {
    @FunctionalInterface
    interface FileReplacement {
        void replace(Path target, String contents);
    }

    private final Path path;
    private final FileReplacement files;
    private final GuideDisplayConfigLoader loader = new GuideDisplayConfigLoader();
    private final GuideDisplayConfigWriter writer = new GuideDisplayConfigWriter();
    private volatile GuideDisplayConfig config = GuideDisplayConfig.defaults();
    private volatile GuideFailure failure;

    public GuideDisplayRuntime(Path path) {
        this(path, new AtomicSettingsFile()::replace);
    }

    GuideDisplayRuntime(Path path, FileReplacement files) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.files = Objects.requireNonNull(files, "files");
        reload();
    }

    public GuideDisplayConfig config() {
        return config;
    }

    public GuideFailure failure() {
        return failure;
    }

    public synchronized ToolResult<GuideDisplayConfig> save(GuideDisplayConfig candidate) {
        Objects.requireNonNull(candidate, "candidate");
        GuideDisplayConfig validated;
        String encoded;
        try {
            encoded = writer.encode(candidate);
            GuideDisplayConfigLoader.Load decoded = loader.load(new StringReader(encoded));
            if (decoded.failure() != null) {
                return fail(decoded.failure());
            }
            validated = decoded.config();
        } catch (RuntimeException invalid) {
            return fail(new GuideFailure(
                    "invalid_display_config", "Unable to prepare display settings"));
        }

        try {
            files.replace(path, encoded);
        } catch (SettingsWriteException writeFailure) {
            return fail(new GuideFailure(writeFailure.code(), writeFailure.getMessage()));
        } catch (RuntimeException writeFailure) {
            return fail(new GuideFailure(
                    "settings_write_failed", "Unable to save settings"));
        }

        config = validated;
        failure = null;
        return new ToolResult.Success<>(validated);
    }

    public synchronized ToolResult<GuideDisplayConfig> reload() {
        GuideDisplayConfigLoader.Load loaded = loader.load(path);
        if (loaded.failure() != null) {
            return fail(loaded.failure());
        }
        config = loaded.config();
        failure = null;
        return new ToolResult.Success<>(config);
    }

    private ToolResult<GuideDisplayConfig> fail(GuideFailure next) {
        failure = Objects.requireNonNull(next, "next");
        return new ToolResult.Failure<>(next.code(), next.message());
    }
}
