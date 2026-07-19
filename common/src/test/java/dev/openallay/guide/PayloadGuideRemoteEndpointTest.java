package dev.openallay.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.openallay.agent.AgentEvent;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.CapabilityPayload;
import dev.openallay.bridge.protocol.ServerAgentEventPayload;
import dev.openallay.bridge.protocol.ServerAgentRequestPayload;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class PayloadGuideRemoteEndpointTest {
    @Test
    void sendsDetachedVisibleHistoryWithServerRequests() {
        FakePort port = new FakePort();
        PayloadGuideRemoteEndpoint endpoint = new PayloadGuideRemoteEndpoint(port, new Gson());
        UUID request = UUID.randomUUID();
        UUID previous = UUID.randomUUID();

        assertTrue(endpoint.ask(
                request,
                "main",
                "follow up",
                List.of(
                        new GuideMessage(
                                previous,
                                GuideMessage.Role.USER,
                                "old question",
                                Instant.EPOCH),
                        new GuideMessage(
                                previous,
                                GuideMessage.Role.ASSISTANT,
                                "old answer",
                                Instant.EPOCH.plusSeconds(1))),
                ignored -> {}));

        assertEquals(request, port.request.requestId());
        assertEquals(
                List.of("USER:old question", "ASSISTANT:old answer"),
                port.request.history().stream()
                        .map(message -> message.role() + ":" + message.content().getFirst().text())
                        .toList());
    }

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
        private ServerAgentRequestPayload request;
        private final List<UUID> cancelled = new ArrayList<>();
        @Override public CapabilityPayload capabilities() {
            return new CapabilityPayload(
                    BridgeProtocol.VERSION, List.of(), true,
                    256_000, 8_192, 2_000, "test/model");
        }
        @Override public boolean ask(
                ServerAgentRequestPayload request, Consumer<ServerAgentEventPayload> events) {
            this.request = request;
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
