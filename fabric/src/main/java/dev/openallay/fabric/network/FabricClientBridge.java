package dev.openallay.fabric.network;

import com.google.gson.Gson;
import dev.openallay.agent.tool.ToolRuntimeCatalog;
import dev.openallay.bridge.client.ClientToolExecutionEndpoint;
import dev.openallay.bridge.client.RemoteCapabilityStore;
import dev.openallay.bridge.client.RemoteToolExecutor;
import dev.openallay.bridge.protocol.BridgeJsonCodec;
import dev.openallay.bridge.protocol.CapabilityPayload;
import dev.openallay.bridge.protocol.ClientToolCallPayload;
import dev.openallay.bridge.protocol.ClientToolCancelPayload;
import dev.openallay.bridge.protocol.RemoteToolResultChunkPayload;
import dev.openallay.bridge.protocol.ServerAgentEventPayload;
import dev.openallay.bridge.protocol.ServerAgentEventChunkPayload;
import dev.openallay.bridge.protocol.ResultChunker;
import dev.openallay.bridge.protocol.ServerAgentRequestPayload;
import dev.openallay.bridge.protocol.ServerAgentRequestChunker;
import dev.openallay.bridge.protocol.ServerAgentCancelPayload;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class FabricClientBridge {
    private final BridgeJsonCodec codec = new BridgeJsonCodec();
    private final RemoteCapabilityStore capabilities = new RemoteCapabilityStore();
    private final Map<UUID, Consumer<ServerAgentEventPayload>> serverRequests =
            new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> agentEventIds = new ConcurrentHashMap<>();
    private final Object serverRequestLock = new Object();
    private final ServerAgentRequestChunker requestChunker = new ServerAgentRequestChunker();
    private final ResultChunker.Reassembler agentEventChunks = new ResultChunker.Reassembler();
    private final java.util.List<Runnable> disconnectListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<Runnable> capabilityListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Supplier<ToolRuntimeCatalog> localToolCatalog;
    private volatile ClientToolExecutionEndpoint clientTools;
    private final RemoteToolExecutor remoteTools = new RemoteToolExecutor(
            capabilities,
            new RemoteToolExecutor.Transport() {
                @Override
                public void call(dev.openallay.bridge.protocol.RemoteToolCallPayload payload) {
                    send("tool_call", payload);
                }

                @Override
                public void cancel(dev.openallay.bridge.protocol.RemoteCancelPayload payload) {
                    if (ClientPlayNetworking.canSend(FabricBridgePayloads.Packet.TYPE)) {
                        send("tool_cancel", payload);
                    }
                }
            });

    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                FabricBridgePayloads.Packet.TYPE,
                (packet, context) -> context.client().execute(() -> receive(packet)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            disconnectState();
            disconnectListeners.forEach(Runnable::run);
        });
    }

    public RemoteToolExecutor remoteTools() {
        return remoteTools;
    }

    public CapabilityPayload capabilities() {
        return capabilities.snapshot();
    }

    /** Installs the local, dynamically filtered Tool source used by future server-model requests. */
    public void configureClientTools(
            Supplier<ToolRuntimeCatalog> localToolCatalog,
            ClientToolExecutionEndpoint.ContextProvider contexts,
            Gson gson,
            dev.openallay.resource.runtime.ResourceRequestRegistry resources) {
        this.localToolCatalog = java.util.Objects.requireNonNull(
                localToolCatalog, "localToolCatalog");
        this.clientTools = new ClientToolExecutionEndpoint(
                contexts,
                chunk -> {
                    net.minecraft.client.Minecraft.getInstance().execute(() -> {
                        if (ClientPlayNetworking.canSend(FabricBridgePayloads.Packet.TYPE)) {
                            send("client_tool_result", chunk);
                        }
                    });
                },
                gson,
                dev.openallay.bridge.protocol.BridgeProtocol.TRANSPORT_CHUNK_BYTES,
                resources,
                () -> {
                    CapabilityPayload capability = capabilities.snapshot();
                    if (!capability.serverModel()) {
                        throw new IllegalStateException("Server model context budget is unavailable");
                    }
                    return new dev.openallay.agent.context.ContextBudget(
                            capability.serverContextWindowTokens(),
                            capability.serverMaxOutputTokens());
                });
        remoteTools.configureResources(resources);
    }

    public void onDisconnect(Runnable listener) { disconnectListeners.add(listener); }
    public void onCapabilitiesChanged(Runnable listener) { capabilityListeners.add(listener); }
    public void disconnectState() {
        capabilities.clear();
        remoteTools.disconnect();
        synchronized (serverRequestLock) {
            serverRequests.clear();
            agentEventIds.clear();
            agentEventChunks.clear();
        }
        ClientToolExecutionEndpoint endpoint = clientTools;
        if (endpoint != null) endpoint.disconnect();
    }

    public boolean askServer(
            ServerAgentRequestPayload request, Consumer<ServerAgentEventPayload> events) {
        if (!capabilities.snapshot().serverModel()
                || !ClientPlayNetworking.canSend(FabricBridgePayloads.Packet.TYPE)) {
            return false;
        }
        ClientToolExecutionEndpoint endpoint = clientTools;
        ServerAgentRequestPayload outbound = request.withClientToolIds(java.util.List.of());
        if (endpoint != null) {
            Supplier<ToolRuntimeCatalog> catalogs = localToolCatalog;
            if (catalogs == null) return false;
            dev.openallay.tool.ToolResult<ClientToolExecutionEndpoint.OpenedRequest> opened =
                    endpoint.open(request.requestId(), request.sessionId(), catalogs.get());
            if (!(opened instanceof dev.openallay.tool.ToolResult.Success<
                    ClientToolExecutionEndpoint.OpenedRequest> success)) {
                return false;
            }
            outbound = request.withClientToolIds(success.value().clientToolIds());
        }
        synchronized (serverRequestLock) {
            serverRequests.put(request.requestId(), events);
        }
        try {
            for (var chunk : requestChunker.split(
                    outbound.requestId(), codec.encode(outbound),
                    dev.openallay.bridge.protocol.BridgeProtocol.TRANSPORT_CHUNK_BYTES)) {
                send("agent_request_chunk", chunk);
            }
            return true;
        } catch (RuntimeException failure) {
            synchronized (serverRequestLock) {
                serverRequests.remove(request.requestId());
                clearAgentEventChunksLocked(request.requestId());
            }
            if (endpoint != null) endpoint.close(request.requestId());
            return false;
        }
    }

    public boolean cancelServer(UUID requestId) {
        Consumer<ServerAgentEventPayload> removed;
        synchronized (serverRequestLock) {
            removed = serverRequests.remove(requestId);
            clearAgentEventChunksLocked(requestId);
        }
        ClientToolExecutionEndpoint endpoint = clientTools;
        if (endpoint != null) endpoint.close(requestId);
        if (removed == null || !ClientPlayNetworking.canSend(FabricBridgePayloads.Packet.TYPE)) {
            return false;
        }
        send("agent_cancel", new ServerAgentCancelPayload(
                dev.openallay.bridge.protocol.BridgeProtocol.VERSION, requestId));
        return true;
    }

    private void receive(FabricBridgePayloads.Packet packet) {
        switch (packet.kind()) {
            case "capabilities" -> {
                capabilities.replace(codec.decode(packet.json(), CapabilityPayload.class));
                capabilityListeners.forEach(Runnable::run);
            }
            case "tool_result" -> remoteTools.receive(
                    codec.decode(packet.json(), RemoteToolResultChunkPayload.class));
            case "client_tool_call" -> {
                ClientToolExecutionEndpoint endpoint = clientTools;
                if (endpoint != null) {
                    endpoint.handle(codec.decode(packet.json(), ClientToolCallPayload.class));
                }
            }
            case "client_tool_cancel" -> {
                ClientToolExecutionEndpoint endpoint = clientTools;
                if (endpoint != null) {
                    endpoint.cancel(codec.decode(packet.json(), ClientToolCancelPayload.class));
                }
            }
            case "agent_event" -> receiveAgentEvent(
                    codec.decode(packet.json(), ServerAgentEventPayload.class));
            case "agent_event_chunk" -> {
                ServerAgentEventChunkPayload chunk =
                        codec.decode(packet.json(), ServerAgentEventChunkPayload.class);
                receiveAgentEventChunk(chunk);
            }
            default -> dev.openallay.OpenAllayConstants.LOGGER.warn(
                    "Ignored unknown client bridge packet {}", packet.kind());
        }
    }

    private void receiveAgentEventChunk(ServerAgentEventChunkPayload chunk) {
        ServerAgentEventPayload completed = null;
        synchronized (serverRequestLock) {
            if (!serverRequests.containsKey(chunk.requestId())) {
                return;
            }
            agentEventIds.computeIfAbsent(
                    chunk.requestId(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(chunk.eventId());
            try {
                java.util.Optional<String> json = agentEventChunks.accept(chunk.asRemoteChunk());
                if (json.isEmpty()) {
                    return;
                }
                forgetAgentEventChunkLocked(chunk.requestId(), chunk.eventId());
                completed = codec.decode(json.orElseThrow(), ServerAgentEventPayload.class);
                if (!completed.requestId().equals(chunk.requestId())) {
                    throw new IllegalArgumentException(
                            "Server Agent event request correlation changed");
                }
            } catch (RuntimeException failure) {
                agentEventChunks.cancel(chunk.eventId());
                forgetAgentEventChunkLocked(chunk.requestId(), chunk.eventId());
                throw failure;
            }
        }
        receiveAgentEvent(completed);
    }

    private void receiveAgentEvent(ServerAgentEventPayload event) {
        Consumer<ServerAgentEventPayload> consumer;
        boolean terminal = event.terminal();
        synchronized (serverRequestLock) {
            consumer = serverRequests.get(event.requestId());
            if (consumer == null) {
                return;
            }
            if (terminal) {
                serverRequests.remove(event.requestId());
                clearAgentEventChunksLocked(event.requestId());
            }
        }
        try {
            consumer.accept(event);
        } finally {
            if (terminal) {
                ClientToolExecutionEndpoint endpoint = clientTools;
                if (endpoint != null) endpoint.close(event.requestId());
            }
        }
    }

    private void clearAgentEventChunksLocked(UUID requestId) {
        Set<UUID> eventIds = agentEventIds.remove(requestId);
        if (eventIds != null) {
            eventIds.forEach(agentEventChunks::cancel);
        }
    }

    private void forgetAgentEventChunkLocked(UUID requestId, UUID eventId) {
        Set<UUID> eventIds = agentEventIds.get(requestId);
        if (eventIds == null) {
            return;
        }
        eventIds.remove(eventId);
        if (eventIds.isEmpty()) {
            agentEventIds.remove(requestId, eventIds);
        }
    }

    private void send(String kind, Object payload) {
        ClientPlayNetworking.send(new FabricBridgePayloads.Packet(kind, codec.encode(payload)));
    }
}
