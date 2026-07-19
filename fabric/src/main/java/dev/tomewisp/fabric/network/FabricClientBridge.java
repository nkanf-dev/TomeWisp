package dev.tomewisp.fabric.network;

import dev.tomewisp.bridge.client.RemoteCapabilityStore;
import dev.tomewisp.bridge.client.RemoteToolExecutor;
import dev.tomewisp.bridge.protocol.BridgeJsonCodec;
import dev.tomewisp.bridge.protocol.CapabilityPayload;
import dev.tomewisp.bridge.protocol.RemoteToolResultChunkPayload;
import dev.tomewisp.bridge.protocol.ServerAgentEventPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestChunker;
import dev.tomewisp.bridge.protocol.ServerAgentCancelPayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class FabricClientBridge {
    private final BridgeJsonCodec codec = new BridgeJsonCodec();
    private final RemoteCapabilityStore capabilities = new RemoteCapabilityStore();
    private final Map<UUID, Consumer<ServerAgentEventPayload>> serverRequests =
            new ConcurrentHashMap<>();
    private final ServerAgentRequestChunker requestChunker = new ServerAgentRequestChunker();
    private final java.util.List<Runnable> disconnectListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<Runnable> capabilityListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final RemoteToolExecutor remoteTools = new RemoteToolExecutor(
            capabilities,
            new RemoteToolExecutor.Transport() {
                @Override
                public void call(dev.tomewisp.bridge.protocol.RemoteToolCallPayload payload) {
                    send("tool_call", payload);
                }

                @Override
                public void cancel(dev.tomewisp.bridge.protocol.RemoteCancelPayload payload) {
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

    public void onDisconnect(Runnable listener) { disconnectListeners.add(listener); }
    public void onCapabilitiesChanged(Runnable listener) { capabilityListeners.add(listener); }
    public void disconnectState() {
        capabilities.clear();
        remoteTools.disconnect();
        serverRequests.clear();
    }

    public boolean askServer(
            ServerAgentRequestPayload request, Consumer<ServerAgentEventPayload> events) {
        if (!capabilities.snapshot().serverModel()
                || !ClientPlayNetworking.canSend(FabricBridgePayloads.Packet.TYPE)) {
            return false;
        }
        serverRequests.put(request.requestId(), events);
        try {
            for (var chunk : requestChunker.split(
                    request.requestId(), codec.encode(request), 24 * 1024)) {
                send("agent_request_chunk", chunk);
            }
            return true;
        } catch (RuntimeException failure) {
            serverRequests.remove(request.requestId());
            return false;
        }
    }

    public boolean cancelServer(UUID requestId) {
        Consumer<ServerAgentEventPayload> removed = serverRequests.remove(requestId);
        if (removed == null || !ClientPlayNetworking.canSend(FabricBridgePayloads.Packet.TYPE)) {
            return false;
        }
        send("agent_cancel", new ServerAgentCancelPayload(
                dev.tomewisp.bridge.protocol.BridgeProtocol.VERSION, requestId));
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
            case "agent_event" -> {
                ServerAgentEventPayload event =
                        codec.decode(packet.json(), ServerAgentEventPayload.class);
                Consumer<ServerAgentEventPayload> consumer = serverRequests.get(event.requestId());
                if (consumer != null) {
                    consumer.accept(event);
                    if (event.terminal()) {
                        serverRequests.remove(event.requestId());
                    }
                }
            }
            default -> dev.tomewisp.TomeWispConstants.LOGGER.warn(
                    "Ignored unknown client bridge packet {}", packet.kind());
        }
    }

    private void send(String kind, Object payload) {
        ClientPlayNetworking.send(new FabricBridgePayloads.Packet(kind, codec.encode(payload)));
    }
}
