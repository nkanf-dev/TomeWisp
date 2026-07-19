package dev.openallay.model.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.model.metadata.ModelMetadata;
import dev.openallay.settings.SettingsWriteException;
import dev.openallay.tool.ToolResult;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModelProfileSettingsStoreTest {
    @TempDir Path temporary;

    @Test
    void atomicallyWritesBeforePublishingPreparedRuntime() throws Exception {
        Path target = temporary.resolve("models.json");
        AtomicBoolean published = new AtomicBoolean();
        ModelProfileSettingsStore store = new ModelProfileSettingsStore(target);

        ToolResult<ModelProfileSettingsStore.Saved> result = store.save(
                config("new-model"),
                Map.of("MODEL_KEY", "secret-value"),
                Map.of(),
                loaded -> () -> {
                    assertTrue(Files.exists(target));
                    assertTrue(read(target).contains("new-model"));
                    assertFalse(read(target).contains("secret-value"));
                    published.set(true);
                });

        ModelProfileSettingsStore.Saved saved = success(result).value();
        assertTrue(published.get());
        assertEquals("new-model", saved.config().profiles().getFirst().model());
        assertTrue(saved.profiles().getFirst().available());
    }

    @Test
    void persistenceFailurePreservesFileAndNeverPublishesPreparedRuntime() throws Exception {
        Path target = temporary.resolve("models.json");
        Files.writeString(target, "old settings\n");
        AtomicBoolean published = new AtomicBoolean();
        ModelProfileSettingsStore store = new ModelProfileSettingsStore(
                target, (ignoredPath, ignoredContents) -> {
                    throw new SettingsWriteException();
                });

        ToolResult<ModelProfileSettingsStore.Saved> result = store.save(
                config("new-model"),
                Map.of("MODEL_KEY", "secret-value"),
                Map.<ModelMetadata.Key, ModelMetadata>of(),
                ignored -> () -> published.set(true));

        ToolResult.Failure<ModelProfileSettingsStore.Saved> failure = failure(result);
        assertEquals("settings_write_failed", failure.code());
        assertEquals("Unable to save settings", failure.message());
        assertEquals("old settings\n", Files.readString(target));
        assertFalse(published.get());
    }

    @Test
    void missingEnvironmentValueMayPersistAsVisibleUnavailableProfile() throws Exception {
        Path target = temporary.resolve("models.json");
        ModelProfileSettingsStore store = new ModelProfileSettingsStore(target);

        ModelProfileSettingsStore.Saved saved = success(store.save(
                config("new-model"), Map.of(), Map.of(), ignored -> () -> {})).value();

        assertFalse(saved.profiles().getFirst().available());
        assertEquals("model_not_configured", saved.profiles().getFirst().failure().code());
        assertTrue(Files.exists(target));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ModelProfilesConfig config(String model) {
        ModelProfileDefinition profile = new ModelProfileDefinition(
                "main",
                "Main",
                true,
                ModelProtocol.OPENAI_CHAT,
                URI.create("https://provider.example/v1"),
                model,
                "MODEL_KEY",
                256_000,
                8_192,
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                null);
        return new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION, profile.id(), List.of(profile));
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<ModelProfileSettingsStore.Saved> success(
            ToolResult<ModelProfileSettingsStore.Saved> result) {
        return (ToolResult.Success<ModelProfileSettingsStore.Saved>)
                assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<ModelProfileSettingsStore.Saved> failure(
            ToolResult<ModelProfileSettingsStore.Saved> result) {
        return (ToolResult.Failure<ModelProfileSettingsStore.Saved>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
