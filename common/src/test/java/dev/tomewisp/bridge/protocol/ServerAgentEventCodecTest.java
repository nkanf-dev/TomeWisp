package dev.tomewisp.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelUsage;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ServerAgentEventCodecTest {
    private final ServerAgentEventCodec codec = new ServerAgentEventCodec(new Gson());

    @Test
    void roundTripsObservableEventsAndTerminalFlags() {
        UUID request = UUID.randomUUID();
        assertEquals(
                "hello",
                assertInstanceOf(
                                ModelEvent.TextDelta.class,
                                assertInstanceOf(
                                                AgentEvent.ModelProgress.class,
                                                codec.decode(codec.encode(
                                                        request,
                                                        new AgentEvent.ModelProgress(
                                                                new ModelEvent.TextDelta("hello"))),
                                                        request))
                                        .event())
                        .text());
        assertEquals(
                new ModelUsage(4, 2, 1),
                assertInstanceOf(
                                ModelEvent.UsageUpdate.class,
                                assertInstanceOf(
                                                AgentEvent.ModelProgress.class,
                                                codec.decode(codec.encode(
                                                        request,
                                                        new AgentEvent.ModelProgress(
                                                                new ModelEvent.UsageUpdate(
                                                                        new ModelUsage(4, 2, 1)))),
                                                        request))
                                        .event())
                        .usage());

        ServerAgentEventPayload terminal =
                codec.encode(request, new AgentEvent.FinalText("done"));
        assertEquals(true, terminal.terminal());
        assertEquals("done", assertInstanceOf(
                AgentEvent.FinalText.class, codec.decode(terminal, request)).text());
    }

    @Test
    void rejectsUnknownMismatchedAndInconsistentEvents() {
        UUID request = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new ServerAgentEventPayload(
                        BridgeProtocol.VERSION, request, "future_event", "{}", false),
                request));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                codec.encode(request, new AgentEvent.FinalText("done")), UUID.randomUUID()));
        ServerAgentEventPayload finalText = codec.encode(request, new AgentEvent.FinalText("done"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new ServerAgentEventPayload(
                        finalText.version(),
                        finalText.requestId(),
                        finalText.eventType(),
                        finalText.eventJson(),
                        false),
                request));
    }

    @Test
    void bridgeCodecAcceptsDedicatedServerAgentCancelOnlyAtCurrentVersion() {
        UUID request = UUID.randomUUID();
        BridgeJsonCodec json = new BridgeJsonCodec();
        ServerAgentCancelPayload value = new ServerAgentCancelPayload(BridgeProtocol.VERSION, request);

        assertEquals(request, json.decode(
                json.encode(value), ServerAgentCancelPayload.class).requestId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerAgentCancelPayload(BridgeProtocol.VERSION + 1, request));
    }
}
