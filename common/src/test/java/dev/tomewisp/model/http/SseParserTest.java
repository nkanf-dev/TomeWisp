package dev.tomewisp.model.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SseParserTest {
    @Test
    void handlesUtf8AndEventBoundariesAcrossArbitraryChunks() {
        List<SseEvent> events = new ArrayList<>();
        SseParser parser = new SseParser(events::add);
        byte[] bytes = "event: content_block_delta\r\ndata: 铁锭\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            parser.accept(new byte[] {value});
        }
        parser.finish();

        assertEquals(List.of(new SseEvent("content_block_delta", "铁锭")), events);
    }

    @Test
    void joinsDataLinesIgnoresCommentsAndFlushesFinalEvent() {
        List<SseEvent> events = new ArrayList<>();
        SseParser parser = new SseParser(events::add);
        parser.accept(": keepalive\ndata: first\ndata: second"
                .getBytes(StandardCharsets.UTF_8));
        parser.finish();

        assertEquals(List.of(new SseEvent("message", "first\nsecond")), events);
    }

    @Test
    void rejectsMalformedUtf8() {
        SseParser parser = new SseParser(event -> {});
        assertThrows(IllegalArgumentException.class, () -> parser.accept(new byte[] {(byte) 0xC3, 0x28}));
    }
}
