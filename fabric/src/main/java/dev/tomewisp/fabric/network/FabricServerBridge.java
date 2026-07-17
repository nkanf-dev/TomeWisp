package dev.tomewisp.fabric.network;

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
import dev.tomewisp.bridge.protocol.ServerAgentCancelPayload;
import dev.tomewisp.bridge.server.ExportedToolPolicy;
import dev.tomewisp.bridge.server.RemoteToolServer;
import dev.tomewisp.context.minecraft.MinecraftContextCapture;
import dev.tomewisp.server.ServerAgentService;
import dev.tomewisp.server.ServerGuideRuntime;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class FabricServerBridge {
    private final TomeWispRuntime runtime;
    private final BridgeJsonCodec codec = new BridgeJsonCodec();
    private final Gson gson = new Gson();
    private final Map<UUID, ServerPlayer> players = new java.util.concurrent.ConcurrentHashMap<>();
    private RemoteToolServer remoteTools;
    private ToolResult<ServerGuideRuntime> serverGuide =
            new ToolResult.Failure<>("model_not_configured", "Server has not started");

    private FabricServerBridge(TomeWispRuntime runtime) {
        this.runtime = runtime;
    }

    public static void register(TomeWispRuntime runtime) {
        FabricServerBridge bridge = new FabricServerBridge(runtime);
        ServerPlayConnectionEvents.JOIN.register(bridge::join);
        ServerPlayConnectionEvents.DISCONNECT.register(bridge::disconnect);
        ServerPlayNetworking.registerGlobalReceiver(
                FabricBridgePayloads.Packet.TYPE, bridge::receive);
    }

    private void join(
            net.minecraft.server.network.ServerGamePacketListenerImpl handler,
            net.fabricmc.fabric.api.networking.v1.PacketSender sender,
            MinecraftServer server) {
        ServerPlayer player = handler.getPlayer();
        players.put(player.getUUID(), player);
        ensureServices(server);
        sender.sendPacket(packet("capabilities", capabilities()));
    }

    private void disconnect(
            net.minecraft.server.network.ServerGamePacketListenerImpl handler,
            MinecraftServer server) {
        UUID actor = handler.getPlayer().getUUID();
        players.remove(actor);
        if (remoteTools != null) {
            remoteTools.disconnect(actor);
        }
        if (serverGuide instanceof ToolResult.Success<ServerGuideRuntime> success) {
            success.value().service().disconnect(actor);
        }
    }

    private void ensureServices(MinecraftServer server) {
        if (remoteTools != null) {
            return;
        }
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
                new ExportedToolPolicy(runtime.tools(), exported),
                contexts,
                (actor, chunk) -> send(actor, "tool_result", chunk),
                new CorrelationRegistry(),
                gson,
                24 * 1024);
        serverGuide = ServerGuideRuntime.create(
                runtime,
                FabricLoader.getInstance().getConfigDir().resolve("tomewisp/server-model.json"),
                System.getenv(),
                contexts::capture,
                (actor, event) -> send(actor, "agent_event", event));
    }

    private void receive(
            FabricBridgePayloads.Packet packet, ServerPlayNetworking.Context context) {
        UUID actor = context.player().getUUID();
        context.server().execute(() -> {
            try {
                switch (packet.kind()) {
                    case "tool_call" -> remoteTools.handle(
                            actor, codec.decode(packet.json(), RemoteToolCallPayload.class));
                    case "tool_cancel" -> remoteTools.cancel(
                            actor, codec.decode(packet.json(), RemoteCancelPayload.class));
                    case "agent_request" -> {
                        if (serverGuide instanceof ToolResult.Success<ServerGuideRuntime> success) {
                            success.value().service().ask(
                                    actor, codec.decode(packet.json(), ServerAgentRequestPayload.class));
                        }
                    }
                    case "agent_cancel" -> {
                        if (serverGuide instanceof ToolResult.Success<ServerGuideRuntime> success) {
                            success.value().service().cancel(
                                    actor,
                                    codec.decode(packet.json(), ServerAgentCancelPayload.class)
                                            .requestId());
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown bridge packet " + packet.kind());
                }
            } catch (RuntimeException failure) {
                dev.tomewisp.TomeWispConstants.LOGGER.warn(
                        "Rejected Fabric bridge packet {} from {}: {}",
                        packet.kind(), actor, failure.getMessage());
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
        return new CapabilityPayload(
                BridgeProtocol.VERSION,
                tools,
                serverGuide instanceof ToolResult.Success<?>);
    }

    private void send(UUID actor, String kind, Object payload) {
        ServerPlayer player = players.get(actor);
        if (player != null && ServerPlayNetworking.canSend(player, FabricBridgePayloads.Packet.TYPE)) {
            ServerPlayNetworking.send(player, packet(kind, payload));
        }
    }

    private FabricBridgePayloads.Packet packet(String kind, Object payload) {
        return new FabricBridgePayloads.Packet(kind, codec.encode(payload));
    }
}
