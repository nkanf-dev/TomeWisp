package dev.tomewisp.bridge.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ServerAgentRequestChunker {
    public List<ServerAgentRequestChunkPayload> split(
            UUID requestId, String content, int transportChunkBytes) {
        if (transportChunkBytes <= 0) {
            throw new IllegalArgumentException("transportChunkBytes must be positive");
        }
        byte[] all = content.getBytes(StandardCharsets.UTF_8);
        String hash = ResultChunker.sha256(all);
        int total = Math.max(1, (all.length + transportChunkBytes - 1) / transportChunkBytes);
        List<ServerAgentRequestChunkPayload> chunks = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int start = index * transportChunkBytes;
            int end = Math.min(all.length, start + transportChunkBytes);
            byte[] part = java.util.Arrays.copyOfRange(all, start, end);
            chunks.add(new ServerAgentRequestChunkPayload(
                    BridgeProtocol.VERSION,
                    requestId,
                    index,
                    total,
                    hash,
                    Base64.getEncoder().encodeToString(part)));
        }
        return List.copyOf(chunks);
    }

    public static final class Reassembler {
        private static final java.util.concurrent.ScheduledExecutorService TIMEOUTS =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "tomewisp-request-chunk-timeouts");
                    thread.setDaemon(true);
                    return thread;
                });
        private final Map<Key, Assembly> assemblies = new HashMap<>();
        private final Duration timeout;

        public Reassembler() {
            this(BridgeProtocol.PARTIAL_ASSEMBLY_TIMEOUT);
        }

        public Reassembler(Duration timeout) {
            this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
        }

        public synchronized java.util.Optional<String> accept(
                UUID actorId, ServerAgentRequestChunkPayload chunk) {
            Key key = new Key(actorId, chunk.requestId());
            Assembly assembly = assemblies.get(key);
            if (assembly == null) {
                assembly = new Assembly(chunk.total(), chunk.contentHash());
                Assembly scheduled = assembly;
                assembly.deadline = TIMEOUTS.schedule(
                        () -> expire(key, scheduled), timeout.toMillis(), TimeUnit.MILLISECONDS);
                assemblies.put(key, assembly);
            }
            if (assembly.total != chunk.total() || !assembly.hash.equals(chunk.contentHash())) {
                remove(key);
                throw new IllegalArgumentException(
                        "Chunk metadata changed during server Agent request assembly");
            }
            byte[] value = Base64.getDecoder().decode(chunk.base64Data());
            byte[] existing = assembly.parts.get(chunk.index());
            if (existing != null) {
                if (!java.util.Arrays.equals(existing, value)) {
                    remove(key);
                    throw new IllegalArgumentException("Duplicate chunk has different content");
                }
                return java.util.Optional.empty();
            }
            assembly.parts.put(chunk.index(), value);
            if (assembly.parts.size() != assembly.total) {
                return java.util.Optional.empty();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (int index = 0; index < assembly.total; index++) {
                output.writeBytes(assembly.parts.get(index));
            }
            byte[] complete = output.toByteArray();
            remove(key);
            if (!ResultChunker.sha256(complete).equals(assembly.hash)) {
                throw new IllegalArgumentException(
                        "Reassembled server Agent request hash does not match");
            }
            return java.util.Optional.of(new String(complete, StandardCharsets.UTF_8));
        }

        public synchronized void cancel(UUID actorId, UUID requestId) {
            remove(new Key(actorId, requestId));
        }

        public synchronized void clearActor(UUID actorId) {
            List<Key> owned = assemblies.keySet().stream()
                    .filter(key -> key.actorId().equals(actorId))
                    .toList();
            owned.forEach(this::remove);
        }

        synchronized int activeAssemblies() {
            return assemblies.size();
        }

        private synchronized void expire(Key key, Assembly expected) {
            assemblies.remove(key, expected);
        }

        private void remove(Key key) {
            Assembly removed = assemblies.remove(key);
            if (removed != null) removed.cancelDeadline();
        }

        private record Key(UUID actorId, UUID requestId) {
            private Key {
                java.util.Objects.requireNonNull(actorId, "actorId");
                java.util.Objects.requireNonNull(requestId, "requestId");
            }
        }

        private static final class Assembly {
            private final int total;
            private final String hash;
            private final Map<Integer, byte[]> parts;
            private volatile ScheduledFuture<?> deadline;

            private Assembly(int total, String hash) {
                this.total = total;
                this.hash = hash;
                parts = new HashMap<>();
            }

            private void cancelDeadline() {
                ScheduledFuture<?> current = deadline;
                if (current != null) current.cancel(false);
            }
        }
    }
}
