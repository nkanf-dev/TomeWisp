package dev.tomewisp.model.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.model.config.ModelProfilesConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModelMetadataBootstrapTest {
    @TempDir Path temporary;

    @Test
    void cacheMissRefreshesInBackgroundPersistsAndReappliesProfiles() throws Exception {
        Path profiles = profiles();
        Path cachePath = temporary.resolve("model-metadata.json");
        List<ModelProfilesConfigLoader.Load> applied = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelMetadataBootstrap bootstrap = new ModelMetadataBootstrap(
                new ModelMetadataCache(cachePath),
                profiles,
                temporary.resolve("model.json"),
                Map.of("OPENROUTER_KEY", "secret"),
                applied::add,
                profile -> (model, explicitContext, explicitOutput, cancellation) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(ModelMetadataResolution.resolved(
                            metadata(256_000, Instant.EPOCH),
                            explicitContext,
                            explicitOutput));
                });

        CompletableFuture<Void> startup = bootstrap.start();
        startup.join();

        assertEquals(1, calls.get());
        assertTrue(applied.size() >= 2);
        assertEquals(256_000, applied.getLast().profiles().getFirst()
                .runtimeConfig().contextWindowTokens());
        assertNull(bootstrap.failure());
        assertTrue(Files.exists(cachePath));
        bootstrap.closeAsync().join();
    }

    @Test
    void validCacheAvoidsStartupNetworkAndManualRefreshReplacesIt() throws Exception {
        Path profiles = profiles();
        Path cachePath = temporary.resolve("model-metadata.json");
        ModelMetadataCache seed = new ModelMetadataCache(cachePath);
        seed.put(metadata(128_000, Instant.EPOCH)).join();
        seed.closeAsync().join();
        List<ModelProfilesConfigLoader.Load> applied = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelMetadataBootstrap bootstrap = new ModelMetadataBootstrap(
                new ModelMetadataCache(cachePath),
                profiles,
                temporary.resolve("model.json"),
                Map.of("OPENROUTER_KEY", "secret"),
                applied::add,
                profile -> (model, explicitContext, explicitOutput, cancellation) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(ModelMetadataResolution.resolved(
                            metadata(512_000, Instant.ofEpochSecond(1)),
                            explicitContext,
                            explicitOutput));
                });

        bootstrap.start().join();
        assertEquals(0, calls.get());
        assertEquals(128_000, applied.getLast().profiles().getFirst()
                .runtimeConfig().contextWindowTokens());

        bootstrap.refreshAll().join();
        assertEquals(1, calls.get());
        assertEquals(512_000, applied.getLast().profiles().getFirst()
                .runtimeConfig().contextWindowTokens());
        bootstrap.closeAsync().join();
    }

    private Path profiles() throws Exception {
        Path path = temporary.resolve("models.json");
        Files.writeString(path, """
                {"schemaVersion":1,"defaultProfileId":"main","profiles":[{
                  "id":"main","displayName":"Main","enabled":true,
                  "protocol":"openai_chat",
                  "baseUrl":"https://openrouter.ai/api/v1",
                  "model":"vendor/model","apiKeyEnv":"OPENROUTER_KEY",
                  "maxOutputTokens":8192,"connectTimeoutSeconds":5,
                  "requestTimeoutSeconds":60
                }]}
                """);
        return path;
    }

    private static ModelMetadata metadata(int context, Instant capturedAt) {
        return new ModelMetadata(
                "openrouter", "vendor/model", "vendor/model-canonical",
                context, 32_000, capturedAt);
    }
}
