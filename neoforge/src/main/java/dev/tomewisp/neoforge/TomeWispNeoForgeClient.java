package dev.tomewisp.neoforge;

import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.client.ClientModelRuntimeRegistry;
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
import dev.tomewisp.guide.ui.GuideDisplayConfigLoader;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.recipe.config.RecipeClientRuntime;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.bus.api.IEventBus;
import dev.tomewisp.neoforge.network.NeoForgeClientBridge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class TomeWispNeoForgeClient {
    private TomeWispNeoForgeClient() {}

    public static void initialize(TomeWispRuntime runtime, IEventBus modBus) {
        NeoForgeClientBridge bridge = new NeoForgeClientBridge();
        bridge.register(modBus);
        Gson gson = new Gson();
        java.time.Clock clock = java.time.Clock.systemUTC();
        var dispatcher = (dev.tomewisp.client.ClientEventDispatcher)
                runnable -> Minecraft.getInstance().execute(runnable);
        java.nio.file.Path configDirectory = FMLPaths.CONFIGDIR.get().resolve("tomewisp");
        ToolResult<ClientModelRuntimeRegistry> guide = ClientModelRuntimeRegistry.create(
                runtime,
                configDirectory.resolve("models.json"),
                configDirectory.resolve("model.json"),
                configDirectory.resolve("model-metadata.json"),
                System.getenv(),
                dispatcher,
                bridge.remoteTools(),
                clock);
        ClientModelRuntimeRegistry modelRegistry =
                guide instanceof ToolResult.Success<ClientModelRuntimeRegistry> success
                ? success.value()
                : null;
        GuideLocalEndpoint local = modelRegistry;
        RecipeClientRuntime recipeClient = new RecipeClientRuntime(
                FMLPaths.CONFIGDIR.get().resolve("tomewisp/recipes.json"));
        var display = new GuideDisplayConfigLoader().load(
                FMLPaths.CONFIGDIR.get().resolve("tomewisp/display.json"));
        MinecraftGuideContextProvider contexts = new MinecraftGuideContextProvider(
                runtime,
                Minecraft.getInstance(),
                gson,
                TomeWispNeoForgeClient.class.getClassLoader(),
                recipeClient);
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
                FMLPaths.CONFIGDIR.get().resolve("tomewisp/history.sqlite3"),
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
        NeoForge.EVENT_BUS.addListener((ClientStoppingEvent event) ->
                services.shutdown()
                        .handle((ignored, failure) -> null)
                        .thenCompose(ignored -> history.closeAsync())
                        .thenCompose(ignored -> modelRegistry == null
                                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                                : modelRegistry.closeAsync()));
        bridge.onCapabilitiesChanged(() -> {
            var current = services.current();
            if (current != null) current.refreshCapabilities();
        });
        dev.tomewisp.guide.GuideScreenOpener screens = service -> {
            Minecraft.getInstance().gui.setScreen(new TomeWispScreen(
                    service, recipeClient, display.config(), display.failure()));
            return new ToolResult.Success<>(true);
        };
        NeoForgeGuideCommands.register(new GuideCommandFacade(
                runtime,
                services,
                contexts,
                screens));
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.registerCategory(TomeWispKeyMappings.CATEGORY);
            event.register(TomeWispKeyMappings.OPEN_GUIDE);
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            Minecraft client = Minecraft.getInstance();
            while (TomeWispKeyMappings.OPEN_GUIDE.consumeClick()) {
                if (client.player != null) screens.open(services.forActor(client.player.getUUID()));
            }
        });
        GuideClientE2EConfig.from(System.getProperties()).ifPresent(config -> {
            String modVersion = ModList.get().getModContainerById("tomewisp")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
            String secret = System.getenv("TOMEWISP_API_KEY");
            GuideClientE2EController controller = new GuideClientE2EController(
                    config,
                    "neoforge",
                    runtime.platform().gameVersion(),
                    modVersion,
                    services,
                    gson,
                    () -> Minecraft.getInstance().stop(),
                    secret == null || secret.isBlank() ? java.util.Set.of() : java.util.Set.of(secret),
                    contexts::recipeProviderReadiness);
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) controller.tick(client.player.getUUID());
            });
        });
    }
}
