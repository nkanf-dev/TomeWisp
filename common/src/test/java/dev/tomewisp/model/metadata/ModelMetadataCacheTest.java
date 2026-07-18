package dev.tomewisp.model.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModelMetadataCacheTest {
    @TempDir Path temporary;

    @Test
    void roundTripsCredentialFreeEntriesAcrossCacheInstances() throws Exception {
        Path path = temporary.resolve("model-metadata.json");
        ModelMetadata metadata = metadata(256_000, Instant.parse("2026-07-18T12:00:00Z"));
        ModelMetadataCache first = new ModelMetadataCache(path);

        ModelMetadataCache.Snapshot written = first.put(metadata).join();
        first.closeAsync().join();

        assertNull(written.failure());
        assertEquals(metadata, written.find("openrouter", "vendor/model"));
        String json = Files.readString(path);
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("Authorization"));
        assertFalse(json.contains("secret"));
        ModelMetadataCache second = new ModelMetadataCache(path);
        assertEquals(metadata, second.load().join()
                .find("openrouter", "vendor/model"));
        second.closeAsync().join();
    }

    @Test
    void replacingOneKeyPreservesOtherEntriesAndLeavesNoTemporaryFile() throws Exception {
        Path path = temporary.resolve("nested/model-metadata.json");
        ModelMetadataCache cache = new ModelMetadataCache(path);
        ModelMetadata first = metadata(128_000, Instant.EPOCH);
        ModelMetadata replacement = metadata(256_000, Instant.ofEpochSecond(1));
        ModelMetadata other = new ModelMetadata(
                "openrouter", "other/model", "other/model", 64_000, null, Instant.EPOCH);

        cache.put(first).join();
        cache.put(other).join();
        ModelMetadataCache.Snapshot snapshot = cache.put(replacement).join();
        cache.closeAsync().join();

        assertEquals(2, snapshot.entries().size());
        assertEquals(replacement, snapshot.find("openrouter", "vendor/model"));
        assertEquals(other, snapshot.find("openrouter", "other/model"));
        try (var files = Files.list(path.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void malformedCacheFailsClosedAndIsNotOverwritten() throws Exception {
        Path path = temporary.resolve("model-metadata.json");
        String malformed = "{\"schemaVersion\":99,\"entries\":[]}";
        Files.writeString(path, malformed);
        ModelMetadataCache cache = new ModelMetadataCache(path);

        ModelMetadataCache.Snapshot loaded = cache.load().join();
        ModelMetadataCache.Snapshot update = cache.put(metadata(256_000, Instant.EPOCH)).join();
        cache.closeAsync().join();

        assertEquals("metadata_cache_invalid", loaded.failure().code());
        assertEquals("metadata_cache_invalid", update.failure().code());
        assertTrue(update.entries().isEmpty());
        assertEquals(malformed, Files.readString(path));
    }

    @Test
    void duplicateKeysAndUnknownFieldsAreRejected() throws Exception {
        Path path = temporary.resolve("model-metadata.json");
        String entry = """
                {"source":"openrouter","providerModelId":"vendor/model",
                 "canonicalModelId":"vendor/model","contextWindowTokens":128000,
                 "maxOutputTokens":null,"capturedAt":"1970-01-01T00:00:00Z"}
                """;
        Files.writeString(path, "{\"schemaVersion\":1,\"entries\":[" + entry + "," + entry + "]}");
        ModelMetadataCache duplicate = new ModelMetadataCache(path);
        assertEquals("metadata_cache_invalid", duplicate.load().join().failure().code());
        duplicate.closeAsync().join();

        Files.writeString(path, "{\"schemaVersion\":1,\"entries\":[],\"extra\":true}");
        ModelMetadataCache unknown = new ModelMetadataCache(path);
        assertEquals("metadata_cache_invalid", unknown.load().join().failure().code());
        unknown.closeAsync().join();
    }

    private static ModelMetadata metadata(int context, Instant capturedAt) {
        return new ModelMetadata(
                "openrouter",
                "vendor/model",
                "vendor/model-canonical",
                context,
                32_000,
                capturedAt);
    }
}
