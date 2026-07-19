package dev.tomewisp.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BridgeJsonCodecTest {
    @Test
    void roundTripsUnicodeAndRejectsUnknownFieldsAndVersions() {
        BridgeJsonCodec codec = new BridgeJsonCodec();
        ServerAgentRequestPayload payload = new ServerAgentRequestPayload(
                BridgeProtocol.VERSION,
                UUID.randomUUID(),
                "machine",
                "压印机怎么搭？",
                true,
                List.of(
                        new ServerAgentHistoryMessage(
                                ServerAgentHistoryMessage.Role.USER, "先前问题"),
                        new ServerAgentHistoryMessage(
                                ServerAgentHistoryMessage.Role.ASSISTANT, "先前回答")));
        assertEquals(payload, codec.decode(codec.encode(payload), ServerAgentRequestPayload.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                codec.encode(payload).replaceFirst("\\{", "{\"extra\":1,"),
                ServerAgentRequestPayload.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                codec.encode(payload).replace(
                        "\"text\":\"先前问题\"",
                        "\"text\":\"先前问题\",\"extra\":true"),
                ServerAgentRequestPayload.class));
        assertThrows(IllegalArgumentException.class, () -> new RemoteCancelPayload(99, UUID.randomUUID()));
    }

    @Test
    void roundTripsStrictRequestScopedClientToolPayloads() {
        BridgeJsonCodec codec = new BridgeJsonCodec();
        UUID requestId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        ServerAgentRequestPayload request = new ServerAgentRequestPayload(
                BridgeProtocol.VERSION,
                requestId,
                "main",
                "读取视频设置",
                true,
                List.of(),
                List.of("tomewisp:inspect_game_state"));
        assertEquals(
                request,
                codec.decode(codec.encode(request), ServerAgentRequestPayload.class));

        ClientToolCallPayload call = new ClientToolCallPayload(
                BridgeProtocol.VERSION,
                requestId,
                invocationId,
                "main",
                "tomewisp:inspect_game_state",
                "{\"section\":\"OPTIONS\"}");
        assertEquals(call, codec.decode(codec.encode(call), ClientToolCallPayload.class));
        ClientToolCancelPayload cancel = new ClientToolCancelPayload(
                BridgeProtocol.VERSION, requestId, invocationId);
        assertEquals(cancel, codec.decode(codec.encode(cancel), ClientToolCancelPayload.class));

        ClientToolResultChunkPayload result = ClientToolResultChunkPayload.from(
                requestId,
                new ResultChunker().split(invocationId, "{\"status\":\"failure\"}", 5)
                        .getFirst());
        assertEquals(
                result,
                codec.decode(codec.encode(result), ClientToolResultChunkPayload.class));
        ServerAgentEventChunkPayload eventChunk = ServerAgentEventChunkPayload.from(
                requestId,
                new ResultChunker().split(invocationId, codec.encode(request), 5).getFirst());
        assertEquals(
                eventChunk,
                codec.decode(codec.encode(eventChunk), ServerAgentEventChunkPayload.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                codec.encode(call).replaceFirst("\\{", "{\"extra\":1,"),
                ClientToolCallPayload.class));
        assertThrows(IllegalArgumentException.class, () -> new ServerAgentRequestPayload(
                BridgeProtocol.VERSION,
                requestId,
                "main",
                "question",
                true,
                List.of(),
                List.of("tomewisp:inspect_game_state", "tomewisp:inspect_game_state")));
        assertTrue(request.clientToolIds().contains("tomewisp:inspect_game_state"));
    }

    @Test
    void reassemblesOutOfOrderChunksWithoutLogicalTruncation() {
        UUID id = UUID.randomUUID();
        String content = "第一层：齿轮箱\n第二层：动力输入 ⚙".repeat(20);
        List<RemoteToolResultChunkPayload> chunks = new ResultChunker().split(id, content, 7);
        ResultChunker.Reassembler reassembler = new ResultChunker.Reassembler();
        java.util.Optional<String> result = java.util.Optional.empty();
        for (RemoteToolResultChunkPayload chunk : chunks.reversed()) {
            java.util.Optional<String> accepted = reassembler.accept(chunk);
            if (accepted.isPresent()) {
                result = accepted;
            }
        }
        assertEquals(content, result.orElseThrow());
    }

    @Test
    void duplicateAndMissingChunksDoNotCompleteEarly() {
        List<RemoteToolResultChunkPayload> chunks =
                new ResultChunker().split(UUID.randomUUID(), "abcdef", 2);
        ResultChunker.Reassembler reassembler = new ResultChunker.Reassembler();
        assertFalse(reassembler.accept(chunks.getFirst()).isPresent());
        assertFalse(reassembler.accept(chunks.getFirst()).isPresent());
        assertFalse(reassembler.accept(chunks.get(1)).isPresent());
        assertEquals("abcdef", reassembler.accept(chunks.getLast()).orElseThrow());
    }

    @Test
    void untrustedHugeChunkCountDoesNotDriveProportionalAllocation() {
        byte[] data = "x".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RemoteToolResultChunkPayload chunk = new RemoteToolResultChunkPayload(
                BridgeProtocol.VERSION,
                UUID.randomUUID(),
                0,
                Integer.MAX_VALUE,
                ResultChunker.sha256(data),
                java.util.Base64.getEncoder().encodeToString(data));

        ResultChunker.Reassembler reassembler = new ResultChunker.Reassembler();
        assertFalse(reassembler.accept(chunk).isPresent());
        reassembler.cancel(chunk.correlationId());
    }

    @Test
    void incompleteResultAssembliesExpire() throws Exception {
        List<RemoteToolResultChunkPayload> chunks =
                new ResultChunker().split(UUID.randomUUID(), "abcdef", 2);
        ResultChunker.Reassembler reassembler =
                new ResultChunker.Reassembler(Duration.ofMillis(20));

        assertFalse(reassembler.accept(chunks.getFirst()).isPresent());
        assertEquals(1, reassembler.activeAssemblies());
        awaitNoAssemblies(reassembler::activeAssemblies);
    }

    @Test
    void serverAgentRequestsChunkUnicodeAndRemainIsolatedByActor() {
        UUID requestId = UUID.randomUUID();
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();
        String content = "跨供应商模型上下文 ⚙".repeat(40);
        ServerAgentRequestChunker chunker = new ServerAgentRequestChunker();
        List<ServerAgentRequestChunkPayload> chunks = chunker.split(requestId, content, 11);
        ServerAgentRequestChunker.Reassembler reassembler =
                new ServerAgentRequestChunker.Reassembler();
        BridgeJsonCodec codec = new BridgeJsonCodec();

        assertEquals(
                chunks.getFirst(),
                codec.decode(
                        codec.encode(chunks.getFirst()),
                        ServerAgentRequestChunkPayload.class));
        assertFalse(reassembler.accept(secondActor, chunks.getFirst()).isPresent());
        reassembler.clearActor(secondActor);
        java.util.Optional<String> restored = java.util.Optional.empty();
        for (ServerAgentRequestChunkPayload chunk : chunks.reversed()) {
            java.util.Optional<String> accepted = reassembler.accept(firstActor, chunk);
            if (accepted.isPresent()) {
                restored = accepted;
            }
        }
        assertEquals(content, restored.orElseThrow());
    }

    @Test
    void incompleteServerRequestAssembliesExpire() throws Exception {
        UUID actorId = UUID.randomUUID();
        List<ServerAgentRequestChunkPayload> chunks =
                new ServerAgentRequestChunker().split(UUID.randomUUID(), "abcdef", 2);
        ServerAgentRequestChunker.Reassembler reassembler =
                new ServerAgentRequestChunker.Reassembler(Duration.ofMillis(20));

        assertFalse(reassembler.accept(actorId, chunks.getFirst()).isPresent());
        assertEquals(1, reassembler.activeAssemblies());
        awaitNoAssemblies(reassembler::activeAssemblies);
    }

    @Test
    void productionChunksLeaveRoomBelowMinecraftStringLimitAfterBase64AndJson() {
        BridgeJsonCodec codec = new BridgeJsonCodec();
        UUID requestId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        String content = "x".repeat(BridgeProtocol.TRANSPORT_CHUNK_BYTES * 3);

        for (RemoteToolResultChunkPayload raw : new ResultChunker().split(
                invocationId, content, BridgeProtocol.TRANSPORT_CHUNK_BYTES)) {
            ClientToolResultChunkPayload chunk =
                    ClientToolResultChunkPayload.from(requestId, raw);
            assertTrue(codec.encode(chunk).length() < 32_767);
        }
        for (ServerAgentRequestChunkPayload chunk : new ServerAgentRequestChunker().split(
                requestId, content, BridgeProtocol.TRANSPORT_CHUNK_BYTES)) {
            assertTrue(codec.encode(chunk).length() < 32_767);
        }
        for (RemoteToolResultChunkPayload raw : new ResultChunker().split(
                invocationId, content, BridgeProtocol.TRANSPORT_CHUNK_BYTES)) {
            ServerAgentEventChunkPayload chunk =
                    ServerAgentEventChunkPayload.from(requestId, raw);
            assertTrue(codec.encode(chunk).length() < 32_767);
        }
    }

    private static void awaitNoAssemblies(java.util.function.IntSupplier active) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (active.getAsInt() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, active.getAsInt());
    }
}
