package dev.tomewisp.fabric;

import dev.tomewisp.TomeWispBootstrap;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.client.ClientGuideRuntime;
import com.google.gson.Gson;
import dev.tomewisp.client.MinecraftGuideContextProvider;
import dev.tomewisp.client.MinecraftGuideHistoryScope;
import dev.tomewisp.guide.GuideCommandFacade;
import dev.tomewisp.guide.GuideLocalEndpoint;
import dev.tomewisp.guide.GuideServiceManager;
import dev.tomewisp.guide.PayloadGuideRemoteEndpoint;
import dev.tomewisp.guide.history.GuideHistoryCodec;
import dev.tomewisp.guide.history.GuideHistoryRepository;
import dev.tomewisp.guide.history.SqliteGuideHistoryStore;
import dev.tomewisp.guide.e2e.GuideClientE2EConfig;
import dev.tomewisp.guide.e2e.GuideClientE2EController;
import dev.tomewisp.client.gui.TomeWispKeyMappings;
import dev.tomewisp.client.gui.TomeWispScreen;
import dev.tomewisp.tool.ToolResult;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import dev.tomewisp.fabric.network.FabricBridgePayloads;
import dev.tomewisp.fabric.network.FabricClientBridge;

public final class TomeWispFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TomeWispRuntime runtime = TomeWispBootstrap.initialize();
        FabricBridgePayloads.register();
        FabricClientBridge bridge = new FabricClientBridge();
        bridge.register();
        Gson gson = new Gson();
        java.time.Clock clock = java.time.Clock.systemUTC();
        var dispatcher = (dev.tomewisp.client.ClientEventDispatcher)
                runnable -> Minecraft.getInstance().execute(runnable);
        ToolResult<ClientGuideRuntime> guide = ClientGuideRuntime.create(
                runtime,
                FabricLoader.getInstance().getConfigDir().resolve("tomewisp/model.json"),
                System.getenv(),
                dispatcher,
                bridge.remoteTools());
        GuideLocalEndpoint local = guide instanceof ToolResult.Success<ClientGuideRuntime> success
                ? success.value()
                : null;
        MinecraftGuideContextProvider contexts = new MinecraftGuideContextProvider(
                runtime, Minecraft.getInstance(), gson, TomeWispFabricClient.class.getClassLoader());
        PayloadGuideRemoteEndpoint remote = new PayloadGuideRemoteEndpoint(
                new PayloadGuideRemoteEndpoint.Port() {
                    @Override public dev.tomewisp.bridge.protocol.CapabilityPayload capabilities() {
                        return bridge.capabilities();
                    }
                    @Override public boolean ask(
                            dev.tomewisp.bridge.protocol.ServerAgentRequestPayload request,
                            java.util.function.Consumer<dev.tomewisp.bridge.protocol.ServerAgentEventPayload> events) {
                        return bridge.askServer(request, events);
                    }
                    @Override public boolean cancel(java.util.UUID requestId) {
                        return bridge.cancelServer(requestId);
                    }
                    @Override public void disconnect() { bridge.disconnectState(); }
                },
                gson);
        GuideHistoryRepository history = new GuideHistoryRepository(new SqliteGuideHistoryStore(
                FabricLoader.getInstance().getConfigDir().resolve("tomewisp/history.sqlite3"),
                clock,
                new GuideHistoryCodec()));
        GuideServiceManager services = new GuideServiceManager(
                local,
                remote,
                contexts,
                dispatcher,
                clock,
                gson,
                history,
                new MinecraftGuideHistoryScope(Minecraft.getInstance()));
        bridge.onDisconnect(services::disconnect);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client ->
                services.shutdown()
                        .handle((ignored, failure) -> null)
                        .thenCompose(ignored -> history.closeAsync()));
        bridge.onCapabilitiesChanged(() -> {
            var current = services.current();
            if (current != null) current.refreshCapabilities();
        });
        dev.tomewisp.guide.GuideScreenOpener screens = service -> {
            Minecraft.getInstance().gui.setScreen(new TomeWispScreen(service));
            return new ToolResult.Success<>(true);
        };
        FabricGuideCommands.register(new GuideCommandFacade(
                runtime,
                services,
                contexts,
                screens));
        var openGuide = KeyMappingHelper.registerKeyMapping(TomeWispKeyMappings.OPEN_GUIDE);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuide.consumeClick()) {
                if (client.player != null) screens.open(services.forActor(client.player.getUUID()));
            }
        });
        GuideClientE2EConfig.from(System.getProperties()).ifPresent(config -> {
            String modVersion = FabricLoader.getInstance().getModContainer("tomewisp")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            String secret = System.getenv("TOMEWISP_API_KEY");
            GuideClientE2EController controller = new GuideClientE2EController(
                    config,
                    "fabric",
                    runtime.platform().gameVersion(),
                    modVersion,
                    services,
                    gson,
                    () -> Minecraft.getInstance().stop(),
                    secret == null || secret.isBlank() ? java.util.Set.of() : java.util.Set.of(secret));
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.player != null) controller.tick(client.player.getUUID());
            });
        });
    }
}
