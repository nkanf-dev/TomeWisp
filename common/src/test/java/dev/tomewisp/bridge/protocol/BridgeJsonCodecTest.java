package dev.tomewisp.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BridgeJsonCodecTest {
    @Test
    void roundTripsUnicodeAndRejectsUnknownFieldsAndVersions() {
        BridgeJsonCodec codec = new BridgeJsonCodec();
        ServerAgentRequestPayload payload = new ServerAgentRequestPayload(
                BridgeProtocol.VERSION, UUID.randomUUID(), "machine", "压印机怎么搭？", true);
        assertEquals(payload, codec.decode(codec.encode(payload), ServerAgentRequestPayload.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                codec.encode(payload).replaceFirst("\\{", "{\"extra\":1,"),
                ServerAgentRequestPayload.class));
        assertThrows(IllegalArgumentException.class, () -> new RemoteCancelPayload(99, UUID.randomUUID()));
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
}
