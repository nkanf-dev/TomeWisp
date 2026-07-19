package dev.tomewisp.guide;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.CapabilityPayload;
import dev.tomewisp.bridge.protocol.ServerAgentEventCodec;
import dev.tomewisp.bridge.protocol.ServerAgentEventPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestPayload;
import dev.tomewisp.bridge.protocol.ServerAgentHistoryMessage;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Consumer;

public final class PayloadGuideRemoteEndpoint implements GuideRemoteEndpoint {
    public interface Port {
        CapabilityPayload capabilities();

        boolean ask(
                ServerAgentRequestPayload request,
                Consumer<ServerAgentEventPayload> events);

        boolean cancel(UUID requestId);

        void disconnect();
    }

    private final Port port;
    private final ServerAgentEventCodec events;

    public PayloadGuideRemoteEndpoint(Port port, Gson gson) {
        this.port = Objects.requireNonNull(port, "port");
        events = new ServerAgentEventCodec(gson);
    }

    @Override
    public boolean serverModelAvailable() {
        return port.capabilities().serverModel();
    }

    @Override
    public boolean serverToolsAvailable() {
        return !port.capabilities().remoteTools().isEmpty();
    }

    @Override
    public Optional<GuideContextSpec> contextSpec() {
        CapabilityPayload capability = port.capabilities();
        if (!capability.serverModel()) return Optional.empty();
        try {
            return Optional.of(new GuideContextSpec(
                    new dev.tomewisp.agent.context.ContextBudget(
                            capability.serverContextWindowTokens(),
                            capability.serverMaxOutputTokens()),
                    capability.serverPromptAndToolTokens(),
                    capability.serverCanonicalModelId()));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    @Override
    public boolean ask(
            UUID requestId,
            String sessionId,
            String question,
            Consumer<AgentEvent> consumer) {
        ServerAgentRequestPayload request = new ServerAgentRequestPayload(
                BridgeProtocol.VERSION, requestId, sessionId, question, true);
        return send(request, consumer);
    }

    @Override
    public boolean askWithContext(
            UUID requestId,
            String sessionId,
            String question,
            List<dev.tomewisp.model.ModelMessage> history,
            Consumer<AgentEvent> consumer) {
        List<ServerAgentHistoryMessage> detached = history.stream()
                .map(ServerAgentHistoryMessage::from)
                .toList();
        return send(new ServerAgentRequestPayload(
                BridgeProtocol.VERSION, requestId, sessionId, question, true, detached), consumer);
    }

    @Override
    public boolean ask(
            UUID requestId,
            String sessionId,
            String question,
            List<GuideMessage> history,
            Consumer<AgentEvent> consumer) {
        List<ServerAgentHistoryMessage> detached = history.stream()
                .map(message -> new ServerAgentHistoryMessage(
                        message.role() == GuideMessage.Role.USER
                                ? ServerAgentHistoryMessage.Role.USER
                                : ServerAgentHistoryMessage.Role.ASSISTANT,
                        message.text()))
                .toList();
        ServerAgentRequestPayload request = new ServerAgentRequestPayload(
                BridgeProtocol.VERSION, requestId, sessionId, question, true, detached);
        return send(request, consumer);
    }

    private boolean send(
            ServerAgentRequestPayload request, Consumer<AgentEvent> consumer) {
        UUID requestId = request.requestId();
        return port.ask(request, payload -> {
            try {
                consumer.accept(events.decode(payload, requestId));
            } catch (RuntimeException failure) {
                port.cancel(requestId);
                consumer.accept(new AgentEvent.Failed(
                        "server_protocol_error",
                        failure.getMessage() == null
                                ? "Malformed server Agent event"
                                : failure.getMessage()));
            }
        });
    }

    @Override
    public boolean cancel(UUID requestId) {
        return port.cancel(requestId);
    }

    @Override
    public void disconnect() {
        port.disconnect();
    }
}
