package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.CapabilityPayload;
import dev.tomewisp.bridge.protocol.ServerAgentEventPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class PayloadGuideRemoteEndpointTest {
    @Test
    void malformedRemoteEventFailsOnlyItsRequestAndCancelsTransport() {
        FakePort port = new FakePort();
        PayloadGuideRemoteEndpoint endpoint = new PayloadGuideRemoteEndpoint(port, new Gson());
        UUID request = UUID.randomUUID();
        List<AgentEvent> events = new ArrayList<>();

        assertTrue(endpoint.ask(request, "main", "question", events::add));
        port.events.accept(new ServerAgentEventPayload(
                BridgeProtocol.VERSION, request, "future_event", "{}", false));

        AgentEvent.Failed failed = assertInstanceOf(AgentEvent.Failed.class, events.getFirst());
        assertEquals("server_protocol_error", failed.code());
        assertEquals(List.of(request), port.cancelled);
    }

    private static final class FakePort implements PayloadGuideRemoteEndpoint.Port {
        private Consumer<ServerAgentEventPayload> events;
        private final List<UUID> cancelled = new ArrayList<>();
        @Override public CapabilityPayload capabilities() {
            return new CapabilityPayload(BridgeProtocol.VERSION, List.of(), true);
        }
        @Override public boolean ask(
                ServerAgentRequestPayload request, Consumer<ServerAgentEventPayload> events) {
            this.events = events;
            return true;
        }
        @Override public boolean cancel(UUID requestId) {
            cancelled.add(requestId);
            return true;
        }
        @Override public void disconnect() {}
    }
}
