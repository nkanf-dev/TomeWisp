package dev.tomewisp.model.metadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.guide.GuideFailure;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ordered asynchronous cache for validated, credential-free provider metadata. */
public final class ModelMetadataCache {
    private static final int SCHEMA_VERSION = 1;
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "entries");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "source", "providerModelId", "canonicalModelId", "contextWindowTokens",
            "maxOutputTokens", "capturedAt");

    public record Snapshot(
            Map<ModelMetadata.Key, ModelMetadata> entries,
            GuideFailure failure) {
        public Snapshot {
            entries = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }

        public ModelMetadata find(String source, String providerModelId) {
            return entries.get(new ModelMetadata.Key(source, providerModelId));
        }
    }

    private final Path path;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("tomewisp-model-metadata-cache", 0).factory());
    private Snapshot state;
    private boolean closed;

    public ModelMetadataCache(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public synchronized CompletableFuture<Snapshot> load() {
        return submit(this::loadOnWorker);
    }

    public synchronized CompletableFuture<Snapshot> put(ModelMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return submit(() -> putOnWorker(metadata));
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        closed = true;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        worker.execute(() -> {
            completion.complete(null);
            worker.shutdown();
        });
        return completion;
    }

    private synchronized <T> CompletableFuture<T> submit(java.util.function.Supplier<T> task) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "model metadata cache is closed"));
        }
        return CompletableFuture.supplyAsync(task, worker);
    }

    private Snapshot loadOnWorker() {
        if (state != null) {
            return state;
        }
        if (!Files.exists(path)) {
            state = new Snapshot(Map.of(), null);
            return state;
        }
        try {
            state = new Snapshot(decode(Files.readString(path)), null);
        } catch (IOException | RuntimeException failure) {
            state = new Snapshot(
                    Map.of(),
                    new GuideFailure(
                            "metadata_cache_invalid",
                            "The local model metadata cache is invalid"));
        }
        return state;
    }

    private Snapshot putOnWorker(ModelMetadata metadata) {
        Snapshot loaded = loadOnWorker();
        if (loaded.failure() != null) {
            return loaded;
        }
        LinkedHashMap<ModelMetadata.Key, ModelMetadata> updated =
                new LinkedHashMap<>(loaded.entries());
        updated.put(metadata.key(), metadata);
        try {
            writeAtomically(encode(updated.values().stream().toList()));
            state = new Snapshot(updated, null);
            return state;
        } catch (IOException failure) {
            return new Snapshot(
                    loaded.entries(),
                    new GuideFailure(
                            "metadata_cache_write_failed",
                            "Unable to update the local model metadata cache"));
        }
    }

    private void writeAtomically(String json) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(
                parent == null ? Path.of(".") : parent,
                path.getFileName().toString(),
                ".tmp");
        try {
            Files.writeString(temporary, json);
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String encode(List<ModelMetadata> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray encodedEntries = new JsonArray();
        for (ModelMetadata metadata : entries) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("source", metadata.source());
            encoded.addProperty("providerModelId", metadata.providerModelId());
            encoded.addProperty("canonicalModelId", metadata.canonicalModelId());
            encoded.addProperty("contextWindowTokens", metadata.contextWindowTokens());
            if (metadata.maxOutputTokens() == null) {
                encoded.add("maxOutputTokens", com.google.gson.JsonNull.INSTANCE);
            } else {
                encoded.addProperty("maxOutputTokens", metadata.maxOutputTokens());
            }
            encoded.addProperty("capturedAt", metadata.capturedAt().toString());
            encodedEntries.add(encoded);
        }
        root.add("entries", encodedEntries);
        return root.toString();
    }

    private static Map<ModelMetadata.Key, ModelMetadata> decode(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        JsonObject root = object(parsed, "metadata cache");
        requireFields(root, ROOT_FIELDS, "metadata cache");
        if (integer(root.get("schemaVersion")) != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported metadata cache schema");
        }
        JsonElement entries = root.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            throw new IllegalArgumentException("metadata cache entries must be an array");
        }
        LinkedHashMap<ModelMetadata.Key, ModelMetadata> decoded = new LinkedHashMap<>();
        for (JsonElement entry : entries.getAsJsonArray()) {
            JsonObject encoded = object(entry, "metadata cache entry");
            requireFields(encoded, ENTRY_FIELDS, "metadata cache entry");
            JsonElement output = encoded.get("maxOutputTokens");
            Integer maxOutput = output == null || output.isJsonNull() ? null : integer(output);
            ModelMetadata metadata = new ModelMetadata(
                    string(encoded, "source"),
                    string(encoded, "providerModelId"),
                    string(encoded, "canonicalModelId"),
                    integer(encoded.get("contextWindowTokens")),
                    maxOutput,
                    Instant.parse(string(encoded, "capturedAt")));
            if (decoded.put(metadata.key(), metadata) != null) {
                throw new IllegalArgumentException("duplicate metadata cache key");
            }
        }
        return decoded;
    }

    private static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return value.getAsString();
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("value must be an integer");
        }
        try {
            return new java.math.BigDecimal(value.getAsString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException("value must be an integer", failure);
        }
    }

    private static void requireFields(JsonObject object, Set<String> expected, String label) {
        if (!object.keySet().equals(expected)) {
            throw new IllegalArgumentException(label + " schema mismatch");
        }
    }
}
