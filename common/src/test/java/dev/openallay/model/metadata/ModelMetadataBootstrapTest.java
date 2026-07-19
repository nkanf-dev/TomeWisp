package dev.openallay.model.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void cacheMissRefreshesInBackgroundPersistsAndPublishesCacheUpdate() throws Exception {
        Path profiles = profiles();
        Path cachePath = temporary.resolve("model-metadata.json");
        List<ModelMetadataUpdate> updates = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelMetadataBootstrap bootstrap = new ModelMetadataBootstrap(
                new ModelMetadataCache(cachePath),
                profiles,
                temporary.resolve("model.json"),
                Map.of("OPENROUTER_KEY", "secret"),
                updates::add,
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
        assertTrue(updates.size() >= 2);
        assertEquals(256_000, updates.getLast().entries().get(
                new ModelMetadata.Key("openrouter", "vendor/model")).contextWindowTokens());
        assertNull(updates.getLast().failure());
        assertTrue(updates.stream().noneMatch(update -> update.toString().contains("secret")));
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
        List<ModelMetadataUpdate> updates = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelMetadataBootstrap bootstrap = new ModelMetadataBootstrap(
                new ModelMetadataCache(cachePath),
                profiles,
                temporary.resolve("model.json"),
                Map.of("OPENROUTER_KEY", "secret"),
                updates::add,
                profile -> (model, explicitContext, explicitOutput, cancellation) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(ModelMetadataResolution.resolved(
                            metadata(512_000, Instant.ofEpochSecond(1)),
                            explicitContext,
                            explicitOutput));
                });

        bootstrap.start().join();
        assertEquals(0, calls.get());
        assertEquals(128_000, updates.getLast().entries().get(
                new ModelMetadata.Key("openrouter", "vendor/model")).contextWindowTokens());

        bootstrap.refreshAll().join();
        assertEquals(1, calls.get());
        assertEquals(512_000, updates.getLast().entries().get(
                new ModelMetadata.Key("openrouter", "vendor/model")).contextWindowTokens());
        bootstrap.closeAsync().join();
    }

    @Test
    void publishedUpdateDefensivelyCopiesEntries() {
        java.util.HashMap<ModelMetadata.Key, ModelMetadata> mutable = new java.util.HashMap<>();
        ModelMetadata original = metadata(128_000, Instant.EPOCH);
        mutable.put(original.key(), original);

        ModelMetadataUpdate update = new ModelMetadataUpdate(mutable, null);
        mutable.clear();

        assertEquals(Map.of(original.key(), original), update.entries());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> update.entries().clear());
    }

    @Test
    void customInferenceHostStillRefreshesTrustedCanonicalMetadata() throws Exception {
        Path profiles = temporary.resolve("custom-models.json");
        Files.writeString(profiles, """
                {"schemaVersion":1,"defaultProfileId":"main","profiles":[{
                  "id":"main","displayName":"Main","enabled":true,
                  "protocol":"openai_chat","baseUrl":"https://provider.example/v1",
                  "model":"deepseek-v4-flash","apiKeyEnv":"CUSTOM_KEY",
                  "maxOutputTokens":4096,"connectTimeoutSeconds":5,
                  "requestTimeoutSeconds":60
                }]}
                """);
        Path cachePath = temporary.resolve("custom-metadata.json");
        AtomicInteger calls = new AtomicInteger();
        ModelMetadataBootstrap bootstrap = new ModelMetadataBootstrap(
                new ModelMetadataCache(cachePath), profiles, temporary.resolve("legacy.json"),
                Map.of("CUSTOM_KEY", "secret"), ignored -> {},
                profile -> (model, explicitContext, explicitOutput, cancellation) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(ModelMetadataResolution.resolved(
                            new ModelMetadata("openrouter", model,
                                    "deepseek/deepseek-v4-flash", 1_000_000, null, Instant.EPOCH),
                            explicitContext, explicitOutput));
                });

        bootstrap.refreshAll().join();

        assertEquals(1, calls.get());
        assertTrue(Files.exists(cachePath));
        assertEquals(1_000_000, new ModelMetadataCache(cachePath).load().join().entries()
                .get(new ModelMetadata.Key("openrouter", "deepseek-v4-flash"))
                .contextWindowTokens());
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
