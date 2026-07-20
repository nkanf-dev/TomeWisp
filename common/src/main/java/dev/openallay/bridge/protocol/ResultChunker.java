package dev.openallay.bridge.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ResultChunker {
    public List<RemoteToolResultChunkPayload> split(
            UUID correlationId, String content, int transportChunkBytes) {
        return split(
                correlationId,
                BridgeViewIdentity.forOperation(correlationId),
                content,
                transportChunkBytes);
    }

    public List<RemoteToolResultChunkPayload> split(
            UUID correlationId, String viewId, String content, int transportChunkBytes) {
        if (transportChunkBytes <= 0) {
            throw new IllegalArgumentException("transportChunkBytes must be positive");
        }
        byte[] all = content.getBytes(StandardCharsets.UTF_8);
        viewId = BridgeViewIdentity.require(viewId);
        String hash = sha256(all);
        int total = Math.max(1, (all.length + transportChunkBytes - 1) / transportChunkBytes);
        List<RemoteToolResultChunkPayload> chunks = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int start = index * transportChunkBytes;
            int end = Math.min(all.length, start + transportChunkBytes);
            byte[] part = java.util.Arrays.copyOfRange(all, start, end);
            chunks.add(new RemoteToolResultChunkPayload(
                    BridgeProtocol.VERSION,
                    correlationId,
                    index,
                    total,
                    viewId,
                    hash,
                    Base64.getEncoder().encodeToString(part)));
        }
        return List.copyOf(chunks);
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static final class Reassembler {
        private static final java.util.concurrent.ScheduledExecutorService TIMEOUTS =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "openallay-result-chunk-timeouts");
                    thread.setDaemon(true);
                    return thread;
                });
        private final Map<UUID, Assembly> assemblies = new HashMap<>();
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

        public synchronized java.util.Optional<String> accept(RemoteToolResultChunkPayload chunk) {
            Assembly assembly = assemblies.get(chunk.correlationId());
            if (assembly == null) {
                assembly = new Assembly(chunk.total(), chunk.viewId(), chunk.contentHash());
                Assembly scheduled = assembly;
                assembly.deadline = TIMEOUTS.schedule(
                        () -> expire(chunk.correlationId(), scheduled),
                        timeout.toMillis(),
                        TimeUnit.MILLISECONDS);
                assemblies.put(chunk.correlationId(), assembly);
            }
            if (assembly.total != chunk.total()
                    || !assembly.viewId.equals(chunk.viewId())
                    || !assembly.hash.equals(chunk.contentHash())) {
                remove(chunk.correlationId());
                throw new IllegalArgumentException("Chunk metadata changed during result assembly");
            }
            byte[] value = Base64.getDecoder().decode(chunk.base64Data());
            byte[] existing = assembly.parts.get(chunk.index());
            if (existing != null) {
                if (!java.util.Arrays.equals(existing, value)) {
                    remove(chunk.correlationId());
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
            remove(chunk.correlationId());
            if (!sha256(complete).equals(assembly.hash)) {
                throw new IllegalArgumentException("Reassembled result hash does not match");
            }
            return java.util.Optional.of(new String(complete, StandardCharsets.UTF_8));
        }

        public synchronized void cancel(UUID correlationId) {
            remove(correlationId);
        }

        public synchronized void clear() {
            assemblies.values().forEach(Assembly::cancelDeadline);
            assemblies.clear();
        }

        public synchronized int activeAssemblies() {
            return assemblies.size();
        }

        private synchronized void expire(UUID correlationId, Assembly expected) {
            assemblies.remove(correlationId, expected);
        }

        private void remove(UUID correlationId) {
            Assembly removed = assemblies.remove(correlationId);
            if (removed != null) {
                removed.cancelDeadline();
            }
        }

        private static final class Assembly {
            private final int total;
            private final String viewId;
            private final String hash;
            private final Map<Integer, byte[]> parts;
            private volatile ScheduledFuture<?> deadline;

            private Assembly(int total, String viewId, String hash) {
                this.total = total;
                this.viewId = viewId;
                this.hash = hash;
                // Network metadata must never drive a proportional allocation before the
                // corresponding packets have actually arrived.
                parts = new HashMap<>();
            }

            private void cancelDeadline() {
                ScheduledFuture<?> current = deadline;
                if (current != null) current.cancel(false);
            }
        }
    }
}
