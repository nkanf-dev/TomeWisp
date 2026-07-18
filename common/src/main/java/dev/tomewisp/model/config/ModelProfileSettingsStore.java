package dev.tomewisp.model.config;

import dev.tomewisp.client.ClientModelRuntimeRegistry;
import dev.tomewisp.model.metadata.ModelMetadata;
import dev.tomewisp.settings.AtomicSettingsFile;
import dev.tomewisp.settings.SettingsWriteException;
import dev.tomewisp.tool.ToolResult;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates and prepares a complete model registry before atomically replacing its file. */
public final class ModelProfileSettingsStore {
    @FunctionalInterface
    interface FileReplacement {
        void replace(Path target, String contents);
    }

    @FunctionalInterface
    interface RuntimePreparer {
        RuntimePublication prepare(ModelProfilesConfigLoader.Load load);
    }

    @FunctionalInterface
    interface RuntimePublication {
        void publish();
    }

    public record Saved(
            ModelProfilesConfig config,
            List<ResolvedModelProfile> profiles) {
        public Saved {
            Objects.requireNonNull(config, "config");
            profiles = List.copyOf(profiles);
            if (profiles.size() != config.profiles().size()) {
                throw new IllegalArgumentException("every saved profile must have a resolution");
            }
        }
    }

    private final Path path;
    private final FileReplacement files;
    private final ModelProfilesConfigWriter writer = new ModelProfilesConfigWriter();
    private final ModelProfilesConfigLoader loader = new ModelProfilesConfigLoader();

    public ModelProfileSettingsStore(Path path) {
        this(path, new AtomicSettingsFile()::replace);
    }

    ModelProfileSettingsStore(Path path, FileReplacement files) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.files = Objects.requireNonNull(files, "files");
    }

    public ToolResult<Saved> save(
            ModelProfilesConfig candidate,
            Map<String, String> environment,
            Map<ModelMetadata.Key, ModelMetadata> metadata,
            ClientModelRuntimeRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        return save(
                candidate,
                environment,
                metadata,
                load -> registry.prepare(load)::publish);
    }

    ToolResult<Saved> save(
            ModelProfilesConfig candidate,
            Map<String, String> environment,
            Map<ModelMetadata.Key, ModelMetadata> metadata,
            RuntimePreparer preparer) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(preparer, "preparer");

        String encoded;
        ModelProfilesConfigLoader.Load resolved;
        RuntimePublication publication;
        try {
            encoded = writer.encode(candidate);
            ToolResult<ModelProfilesConfigLoader.Load> loaded = loader.load(
                    new StringReader(encoded), Map.copyOf(environment), Map.copyOf(metadata));
            if (loaded instanceof ToolResult.Failure<ModelProfilesConfigLoader.Load> failure) {
                return new ToolResult.Failure<>(failure.code(), failure.message());
            }
            resolved = ((ToolResult.Success<ModelProfilesConfigLoader.Load>) loaded).value();
            publication = Objects.requireNonNull(
                    preparer.prepare(resolved), "runtime publication");
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "invalid_model_config", "Unable to prepare model profile settings");
        }

        try {
            files.replace(path, encoded);
        } catch (SettingsWriteException failure) {
            return new ToolResult.Failure<>(failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(
                    "settings_write_failed", "Unable to save settings");
        }

        publication.publish();
        return new ToolResult.Success<>(new Saved(resolved.config(), resolved.profiles()));
    }
}
