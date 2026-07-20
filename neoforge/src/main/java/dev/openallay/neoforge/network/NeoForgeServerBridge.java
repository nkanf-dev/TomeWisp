package dev.openallay.neoforge.network;

import com.google.gson.Gson;
import dev.openallay.OpenAllayRuntime;
import dev.openallay.agent.tool.ToolSchemaGenerator;
import dev.openallay.bridge.CorrelationRegistry;
import dev.openallay.bridge.protocol.BridgeJsonCodec;
import dev.openallay.bridge.protocol.BridgeProtocol;
import dev.openallay.bridge.protocol.CapabilityPayload;
import dev.openallay.bridge.protocol.RemoteCancelPayload;
import dev.openallay.bridge.protocol.RemoteToolCallPayload;
import dev.openallay.bridge.protocol.ServerAgentRequestPayload;
import dev.openallay.bridge.protocol.ServerAgentRequestChunkPayload;
import dev.openallay.bridge.protocol.ServerAgentRequestChunker;
import dev.openallay.bridge.protocol.ServerAgentCancelPayload;
import dev.openallay.bridge.protocol.ServerAgentEventCodec;
import dev.openallay.bridge.server.ExportedToolPolicy;
import dev.openallay.bridge.server.RemoteToolServer;
import dev.openallay.context.minecraft.MinecraftContextCapture;
import dev.openallay.server.ServerGuideRuntime;
import dev.openallay.server.ServerAgentService;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class NeoForgeServerBridge {
    private final OpenAllayRuntime runtime;
    private final BridgeJsonCodec codec = new BridgeJsonCodec();
    private final Gson gson = new Gson();
    private final ServerAgentEventCodec agentEvents = new ServerAgentEventCodec(gson);
    private final Map<UUID, ServerPlayer> players = new java.util.concurrent.ConcurrentHashMap<>();
    private final ServerAgentRequestChunker.Reassembler requestChunks =
            new ServerAgentRequestChunker.Reassembler();
    private RemoteToolServer remoteTools;
    private ToolResult<ServerGuideRuntime> serverGuide =
            new ToolResult.Failure<>("model_not_configured", "Server has not started");

    NeoForgeServerBridge(OpenAllayRuntime runtime) {
        this.runtime = runtime;
    }

    void registerLifecycle() {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                players.put(player.getUUID(), player);
                ensureServices(player.level().getServer());
                send(player.getUUID(), "capabilities", capabilities());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                UUID actor = player.getUUID();
                players.remove(actor);
                requestChunks.clearActor(actor);
                if (remoteTools != null) remoteTools.disconnect(actor);
                if (serverGuide instanceof ToolResult.Success<ServerGuideRuntime> success) {
                    success.value().service().disconnect(actor);
                    success.value().clientTools().disconnect(actor);
                    success.value().resources().disconnectActor(actor);
                }
            }
        });
    }

    void receive(NeoForgeBridgePayloads.Packet packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        UUID actor = player.getUUID();
        try {
            switch (packet.kind()) {
                case "tool_call" -> remoteTools.handle(
                        actor, codec.decode(packet.json(), RemoteToolCallPayload.class));
                case "tool_cancel" -> remoteTools.cancel(
                        actor, codec.decode(packet.json(), RemoteCancelPayload.class));
                case "agent_request_chunk" -> {
                    if (serverGuide instanceof ToolResult.Success<ServerGuideRuntime> success) {
                        ServerAgentRequestChunkPayload chunk = codec.decode(
                                packet.json(), ServerAgentRequestChunkPayload.class);
                        requestChunks.accept(actor, chunk).ifPresent(json -> {
                            ServerAgentRequestPayload request =
                                    codec.decode(json, ServerAgentRequestPayload.class);
                            ToolResult<ServerAgentService.Accepted> accepted =
                                    success.value().service().ask(actor, request);
                            if (accepted instanceof ToolResult.Failure<
                                    ServerAgentService.Accepted> failure) {
                                sendAgentEvent(actor, agentEvents.encode(
                                        request.requestId(),
                                        new dev.openallay.agent.AgentEvent.Failed(
                                                failure.code(), failure.message())));
                            }
                        });
                    }
                }
                case "agent_cancel" -> {
                    if (serverGuide instanceof ToolResult.Success<ServerGuideRuntime> success) {
                        UUID requestId = codec.decode(
                                packet.json(), ServerAgentCancelPayload.class).requestId();
                        requestChunks.cancel(actor, requestId);
                        success.value().service().cancel(actor, requestId);
                    }
                }
                case "client_tool_result" -> {
                    if (serverGuide instanceof ToolResult.Success<ServerGuideRuntime> success) {
                        success.value().clientTools().receive(
                                actor,
                                codec.decode(
                                        packet.json(),
                                        dev.openallay.bridge.protocol.ClientToolResultChunkPayload.class));
                    }
                }
                default -> throw new IllegalArgumentException("Unknown bridge packet " + packet.kind());
            }
        } catch (RuntimeException failure) {
            dev.openallay.OpenAllayConstants.LOGGER.warn(
                    "Rejected NeoForge bridge packet {} from {}: {}",
                    packet.kind(), actor, failure.getMessage());
        }
    }

    private void ensureServices(MinecraftServer server) {
        if (remoteTools != null) return;
        Set<String> exported = runtime.tools().descriptors().stream()
                .filter(descriptor -> descriptor.access() == ToolAccess.READ_ONLY)
                .map(descriptor -> descriptor.id())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        RemoteToolServer.ContextProvider contexts = (actor, capabilities, correlation) -> {
            CompletableFuture<dev.openallay.context.ToolInvocationContext> result = new CompletableFuture<>();
            server.execute(() -> {
                ServerPlayer player = players.get(actor);
                if (player == null) {
                    result.completeExceptionally(new IllegalStateException("Player disconnected"));
                } else {
                    try {
                        result.complete(new MinecraftContextCapture(gson).capture(
                                player.createCommandSourceStack(), capabilities, correlation));
                    } catch (RuntimeException failure) {
                        result.completeExceptionally(failure);
                    }
                }
            });
            return result;
        };
        remoteTools = new RemoteToolServer(
                new ExportedToolPolicy(runtime.tools(), exported), contexts,
                (actor, chunk) -> send(actor, "tool_result", chunk),
                new CorrelationRegistry(), gson, BridgeProtocol.TRANSPORT_CHUNK_BYTES);
        serverGuide = ServerGuideRuntime.create(
                runtime,
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("openallay/server-model.json"),
                System.getenv(),
                contexts::capture,
                this::sendAgentEvent,
                new dev.openallay.bridge.server.PlayerClientToolRouter.Transport() {
                    @Override
                    public boolean call(
                            UUID actor,
                            dev.openallay.bridge.protocol.ClientToolCallPayload payload) {
                        return send(actor, "client_tool_call", payload);
                    }

                    @Override
                    public void cancel(
                            UUID actor,
                            dev.openallay.bridge.protocol.ClientToolCancelPayload payload) {
                        send(actor, "client_tool_cancel", payload);
                    }
                });
    }

    private CapabilityPayload capabilities() {
        ToolSchemaGenerator schemas = new ToolSchemaGenerator();
        List<CapabilityPayload.RemoteToolCapability> tools = runtime.tools().descriptors().stream()
                .filter(descriptor -> descriptor.access() == ToolAccess.READ_ONLY)
                .map(descriptor -> new CapabilityPayload.RemoteToolCapability(
                        descriptor.id(), descriptor.description(),
                        schemas.generate(descriptor.inputType()).toString()))
                .toList();
        if (serverGuide instanceof ToolResult.Success<ServerGuideRuntime> success) {
            var spec = success.value().contextSpec();
            return new CapabilityPayload(
                    BridgeProtocol.VERSION, tools, true,
                    spec.budget().contextWindowTokens(), spec.budget().maxOutputTokens(),
                    spec.promptAndToolTokens(), spec.canonicalModelId());
        }
        return new CapabilityPayload(
                BridgeProtocol.VERSION, tools, false, 0, 0, 0, "");
    }

    private boolean send(UUID actor, String kind, Object payload) {
        ServerPlayer player = players.get(actor);
        if (player != null) {
            PacketDistributor.sendToPlayer(
                    player, new NeoForgeBridgePayloads.Packet(kind, codec.encode(payload)));
            return true;
        }
        return false;
    }

    private void sendAgentEvent(
            UUID actor, dev.openallay.bridge.protocol.ServerAgentEventPayload event) {
        UUID eventId = UUID.randomUUID();
        for (var chunk : new dev.openallay.bridge.protocol.ResultChunker().split(
                eventId,
                codec.encode(event),
                BridgeProtocol.TRANSPORT_CHUNK_BYTES)) {
            send(actor, "agent_event_chunk",
                    dev.openallay.bridge.protocol.ServerAgentEventChunkPayload.from(
                            event.requestId(), chunk));
        }
    }
}
