package dev.tomewisp.neoforge.network;

import com.google.gson.Gson;
import dev.tomewisp.agent.tool.ToolRuntimeCatalog;
import dev.tomewisp.bridge.client.ClientToolExecutionEndpoint;
import dev.tomewisp.bridge.client.RemoteCapabilityStore;
import dev.tomewisp.bridge.client.RemoteToolExecutor;
import dev.tomewisp.bridge.protocol.BridgeJsonCodec;
import dev.tomewisp.bridge.protocol.CapabilityPayload;
import dev.tomewisp.bridge.protocol.ClientToolCallPayload;
import dev.tomewisp.bridge.protocol.ClientToolCancelPayload;
import dev.tomewisp.bridge.protocol.RemoteToolResultChunkPayload;
import dev.tomewisp.bridge.protocol.ServerAgentEventPayload;
import dev.tomewisp.bridge.protocol.ServerAgentEventChunkPayload;
import dev.tomewisp.bridge.protocol.ResultChunker;
import dev.tomewisp.bridge.protocol.ServerAgentRequestPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestChunker;
import dev.tomewisp.bridge.protocol.ServerAgentCancelPayload;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class NeoForgeClientBridge {
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
                public void call(dev.tomewisp.bridge.protocol.RemoteToolCallPayload payload) {
                    send("tool_call", payload);
                }
                @Override
                public void cancel(dev.tomewisp.bridge.protocol.RemoteCancelPayload payload) {
                    send("tool_cancel", payload);
                }
            });

    public void register(IEventBus modBus) {
        modBus.addListener((RegisterClientPayloadHandlersEvent event) ->
                event.register(NeoForgeBridgePayloads.Packet.TYPE, this::receive));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            disconnectState();
            disconnectListeners.forEach(Runnable::run);
        });
    }

    public RemoteToolExecutor remoteTools() { return remoteTools; }
    public CapabilityPayload capabilities() { return capabilities.snapshot(); }
    /** Installs the local, dynamically filtered Tool source used by future server-model requests. */
    public void configureClientTools(
            Supplier<ToolRuntimeCatalog> localToolCatalog,
            ClientToolExecutionEndpoint.ContextProvider contexts,
            Gson gson) {
        this.localToolCatalog = java.util.Objects.requireNonNull(
                localToolCatalog, "localToolCatalog");
        this.clientTools = new ClientToolExecutionEndpoint(
                contexts,
                chunk -> net.minecraft.client.Minecraft.getInstance().execute(
                        () -> send("client_tool_result", chunk)),
                gson,
                dev.tomewisp.bridge.protocol.BridgeProtocol.TRANSPORT_CHUNK_BYTES);
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
        if (!capabilities.snapshot().serverModel()) return false;
        ClientToolExecutionEndpoint endpoint = clientTools;
        ServerAgentRequestPayload outbound = request.withClientToolIds(java.util.List.of());
        if (endpoint != null) {
            Supplier<ToolRuntimeCatalog> catalogs = localToolCatalog;
            if (catalogs == null) return false;
            dev.tomewisp.tool.ToolResult<ClientToolExecutionEndpoint.OpenedRequest> opened =
                    endpoint.open(request.requestId(), request.sessionId(), catalogs.get());
            if (!(opened instanceof dev.tomewisp.tool.ToolResult.Success<
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
                    dev.tomewisp.bridge.protocol.BridgeProtocol.TRANSPORT_CHUNK_BYTES)) {
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
        if (removed == null) return false;
        send("agent_cancel", new ServerAgentCancelPayload(
                dev.tomewisp.bridge.protocol.BridgeProtocol.VERSION, requestId));
        return true;
    }

    private void receive(NeoForgeBridgePayloads.Packet packet, IPayloadContext context) {
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
            default -> dev.tomewisp.TomeWispConstants.LOGGER.warn(
                    "Ignored unknown NeoForge client bridge packet {}", packet.kind());
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
        ClientPacketDistributor.sendToServer(
                new NeoForgeBridgePayloads.Packet(kind, codec.encode(payload)));
    }
}
