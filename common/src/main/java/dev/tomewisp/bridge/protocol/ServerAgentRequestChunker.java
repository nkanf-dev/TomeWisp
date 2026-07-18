package dev.tomewisp.bridge.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        private final Map<Key, Assembly> assemblies = new HashMap<>();

        public synchronized java.util.Optional<String> accept(
                UUID actorId, ServerAgentRequestChunkPayload chunk) {
            Key key = new Key(actorId, chunk.requestId());
            Assembly assembly = assemblies.computeIfAbsent(
                    key, ignored -> new Assembly(chunk.total(), chunk.contentHash()));
            if (assembly.total != chunk.total() || !assembly.hash.equals(chunk.contentHash())) {
                assemblies.remove(key);
                throw new IllegalArgumentException(
                        "Chunk metadata changed during server Agent request assembly");
            }
            byte[] value = Base64.getDecoder().decode(chunk.base64Data());
            byte[] existing = assembly.parts.get(chunk.index());
            if (existing != null) {
                if (!java.util.Arrays.equals(existing, value)) {
                    assemblies.remove(key);
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
            assemblies.remove(key);
            if (!ResultChunker.sha256(complete).equals(assembly.hash)) {
                throw new IllegalArgumentException(
                        "Reassembled server Agent request hash does not match");
            }
            return java.util.Optional.of(new String(complete, StandardCharsets.UTF_8));
        }

        public synchronized void cancel(UUID actorId, UUID requestId) {
            assemblies.remove(new Key(actorId, requestId));
        }

        public synchronized void clearActor(UUID actorId) {
            assemblies.keySet().removeIf(key -> key.actorId().equals(actorId));
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

            private Assembly(int total, String hash) {
                this.total = total;
                this.hash = hash;
                parts = new HashMap<>();
            }
        }
    }
}
