package dev.tomewisp.bridge.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ResultChunker {
    public List<RemoteToolResultChunkPayload> split(
            UUID correlationId, String content, int transportChunkBytes) {
        if (transportChunkBytes <= 0) {
            throw new IllegalArgumentException("transportChunkBytes must be positive");
        }
        byte[] all = content.getBytes(StandardCharsets.UTF_8);
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
        private final Map<UUID, Assembly> assemblies = new HashMap<>();

        public synchronized java.util.Optional<String> accept(RemoteToolResultChunkPayload chunk) {
            Assembly assembly = assemblies.computeIfAbsent(chunk.correlationId(), ignored ->
                    new Assembly(chunk.total(), chunk.contentHash()));
            if (assembly.total != chunk.total() || !assembly.hash.equals(chunk.contentHash())) {
                assemblies.remove(chunk.correlationId());
                throw new IllegalArgumentException("Chunk metadata changed during result assembly");
            }
            byte[] value = Base64.getDecoder().decode(chunk.base64Data());
            if (assembly.received.get(chunk.index())) {
                if (!java.util.Arrays.equals(assembly.parts[chunk.index()], value)) {
                    assemblies.remove(chunk.correlationId());
                    throw new IllegalArgumentException("Duplicate chunk has different content");
                }
                return java.util.Optional.empty();
            }
            assembly.parts[chunk.index()] = value;
            assembly.received.set(chunk.index());
            if (assembly.received.cardinality() != assembly.total) {
                return java.util.Optional.empty();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (byte[] part : assembly.parts) {
                output.writeBytes(part);
            }
            byte[] complete = output.toByteArray();
            assemblies.remove(chunk.correlationId());
            if (!sha256(complete).equals(assembly.hash)) {
                throw new IllegalArgumentException("Reassembled result hash does not match");
            }
            return java.util.Optional.of(new String(complete, StandardCharsets.UTF_8));
        }

        public synchronized void cancel(UUID correlationId) {
            assemblies.remove(correlationId);
        }

        private static final class Assembly {
            private final int total;
            private final String hash;
            private final byte[][] parts;
            private final BitSet received;

            private Assembly(int total, String hash) {
                this.total = total;
                this.hash = hash;
                parts = new byte[total][];
                received = new BitSet(total);
            }
        }
    }
}
