package dev.tomewisp.neoforge.network;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.tool.ToolSchemaGenerator;
import dev.tomewisp.bridge.CorrelationRegistry;
import dev.tomewisp.bridge.protocol.BridgeJsonCodec;
import dev.tomewisp.bridge.protocol.BridgeProtocol;
import dev.tomewisp.bridge.protocol.CapabilityPayload;
import dev.tomewisp.bridge.protocol.RemoteCancelPayload;
import dev.tomewisp.bridge.protocol.RemoteToolCallPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestChunkPayload;
import dev.tomewisp.bridge.protocol.ServerAgentRequestChunker;
import dev.tomewisp.bridge.protocol.ServerAgentCancelPayload;
import dev.tomewisp.bridge.server.ExportedToolPolicy;
import dev.tomewisp.bridge.server.RemoteToolServer;
import dev.tomewisp.context.minecraft.MinecraftContextCapture;
import dev.tomewisp.server.ServerGuideRuntime;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolResult;
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
    private final TomeWispRuntime runtime;
    private final BridgeJsonCodec codec = new BridgeJsonCodec();
    private final Gson gson = new Gson();
    private final Map<UUID, ServerPlayer> players = new java.util.concurrent.ConcurrentHashMap<>();
    private final ServerAgentRequestChunker.Reassembler requestChunks =
            new ServerAgentRequestChunker.Reassembler();
    private RemoteToolServer remoteTools;
    private ToolResult<ServerGuideRuntime> serverGuide =
            new ToolResult.Failure<>("model_not_configured", "Server has not started");

    NeoForgeServerBridge(TomeWispRuntime runtime) {
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
                        requestChunks.accept(actor, chunk).ifPresent(json ->
                                success.value().service().ask(
                                        actor,
                                        codec.decode(json, ServerAgentRequestPayload.class)));
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
                default -> throw new IllegalArgumentException("Unknown bridge packet " + packet.kind());
            }
        } catch (RuntimeException failure) {
            dev.tomewisp.TomeWispConstants.LOGGER.warn(
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
            CompletableFuture<dev.tomewisp.context.ToolInvocationContext> result = new CompletableFuture<>();
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
                new CorrelationRegistry(), gson, 24 * 1024);
        serverGuide = ServerGuideRuntime.create(
                runtime,
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("tomewisp/server-model.json"),
                System.getenv(),
                contexts::capture,
                (actor, event) -> send(actor, "agent_event", event));
    }

    private CapabilityPayload capabilities() {
        ToolSchemaGenerator schemas = new ToolSchemaGenerator();
        List<CapabilityPayload.RemoteToolCapability> tools = runtime.tools().descriptors().stream()
                .filter(descriptor -> descriptor.access() == ToolAccess.READ_ONLY)
                .map(descriptor -> new CapabilityPayload.RemoteToolCapability(
                        descriptor.id(), descriptor.description(),
                        schemas.generate(descriptor.inputType()).toString()))
                .toList();
        return new CapabilityPayload(
                BridgeProtocol.VERSION, tools,
                serverGuide instanceof ToolResult.Success<?>);
    }

    private void send(UUID actor, String kind, Object payload) {
        ServerPlayer player = players.get(actor);
        if (player != null) {
            PacketDistributor.sendToPlayer(
                    player, new NeoForgeBridgePayloads.Packet(kind, codec.encode(payload)));
        }
    }
}
