package dev.tomewisp.model.http;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

public final class SseParser {
    private final Consumer<SseEvent> consumer;
    private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private ByteBuffer carry = ByteBuffer.allocate(0);
    private final StringBuilder pendingText = new StringBuilder();
    private final StringBuilder data = new StringBuilder();
    private String event = "message";

    public SseParser(Consumer<SseEvent> consumer) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    public synchronized void accept(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        ByteBuffer input = ByteBuffer.allocate(carry.remaining() + bytes.length);
        input.put(carry.slice());
        input.put(bytes);
        input.flip();
        decode(input, false);
        carry = ByteBuffer.allocate(input.remaining());
        carry.put(input);
        carry.flip();
        consumeLines(false);
    }

    public synchronized void finish() {
        ByteBuffer input = carry.slice();
        decode(input, true);
        if (input.hasRemaining()) {
            throw new IllegalArgumentException("Incomplete UTF-8 at end of SSE stream");
        }
        try {
            CharBuffer output = CharBuffer.allocate(32);
            while (true) {
                CoderResult result = decoder.flush(output);
                output.flip();
                pendingText.append(output);
                output.clear();
                if (result.isUnderflow()) {
                    break;
                }
                if (result.isError()) {
                    result.throwException();
                }
            }
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 SSE stream", exception);
        }
        consumeLines(true);
        dispatch();
    }

    private void decode(ByteBuffer input, boolean end) {
        try {
            CharBuffer output = CharBuffer.allocate(Math.max(32, input.remaining() * 2 + 2));
            while (true) {
                CoderResult result = decoder.decode(input, output, end);
                output.flip();
                pendingText.append(output);
                output.clear();
                if (result.isUnderflow()) {
                    return;
                }
                if (result.isError()) {
                    result.throwException();
                }
            }
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 SSE stream", exception);
        }
    }

    private void consumeLines(boolean end) {
        int newline;
        while ((newline = pendingText.indexOf("\n")) >= 0) {
            String line = pendingText.substring(0, newline);
            pendingText.delete(0, newline + 1);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            consumeLine(line);
        }
        if (end && !pendingText.isEmpty()) {
            consumeLine(pendingText.toString());
            pendingText.setLength(0);
        }
    }

    private void consumeLine(String line) {
        if (line.isEmpty()) {
            dispatch();
        } else if (line.startsWith(":")) {
            return;
        } else if (line.startsWith("event:")) {
            event = fieldValue(line, "event:");
        } else if (line.startsWith("data:")) {
            if (!data.isEmpty()) {
                data.append('\n');
            }
            data.append(fieldValue(line, "data:"));
        }
    }

    private void dispatch() {
        if (!data.isEmpty()) {
            consumer.accept(new SseEvent(event, data.toString()));
        }
        event = "message";
        data.setLength(0);
    }

    private static String fieldValue(String line, String prefix) {
        String value = line.substring(prefix.length());
        return value.startsWith(" ") ? value.substring(1) : value;
    }
}
