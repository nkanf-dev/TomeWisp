package dev.openallay.fabric;

import dev.openallay.OpenAllayBootstrap;
import dev.openallay.OpenAllayRuntime;
import dev.openallay.client.ClientModelRuntimeRegistry;
import dev.openallay.settings.ClientSettingsRuntime;
import com.google.gson.Gson;
import dev.openallay.client.MinecraftGuideContextProvider;
import dev.openallay.client.MinecraftGuideHistoryScope;
import dev.openallay.guide.GuideCommandFacade;
import dev.openallay.guide.GuideLocalEndpoint;
import dev.openallay.guide.GuideServiceManager;
import dev.openallay.guide.PayloadGuideRemoteEndpoint;
import dev.openallay.guide.history.GuideHistoryCodec;
import dev.openallay.guide.history.GuideHistoryRepository;
import dev.openallay.guide.history.SqliteGuideHistoryStore;
import dev.openallay.guide.e2e.GuideClientE2EConfig;
import dev.openallay.guide.e2e.GuideClientE2EController;
import dev.openallay.client.gui.OpenAllayKeyMappings;
import dev.openallay.client.gui.OpenAllayScreen;
import dev.openallay.client.gui.OpenAllaySettingsScreen;
import dev.openallay.guide.ui.GuideDisplayRuntime;
import dev.openallay.settings.ClientSettingsHistoryBinding;
import dev.openallay.tool.ToolResult;
import dev.openallay.recipe.config.RecipeClientRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import dev.openallay.fabric.network.FabricBridgePayloads;
import dev.openallay.fabric.network.FabricClientBridge;

public final class OpenAllayFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        OpenAllayRuntime runtime = OpenAllayBootstrap.initialize();
        FabricBridgePayloads.register();
        FabricClientBridge bridge = new FabricClientBridge();
        bridge.register();
        Gson gson = new Gson();
        java.time.Clock clock = java.time.Clock.systemUTC();
        var dispatcher = (dev.openallay.client.ClientEventDispatcher)
                runnable -> Minecraft.getInstance().execute(runnable);
        java.nio.file.Path configDirectory =
                FabricLoader.getInstance().getConfigDir().resolve("openallay");
        GuideDisplayRuntime display = new GuideDisplayRuntime(
                configDirectory.resolve("display.json"));
        ClientSettingsHistoryBinding historySettings = new ClientSettingsHistoryBinding();
        RecipeClientRuntime recipeClient = new RecipeClientRuntime(
                configDirectory.resolve("recipes.json"));
        ToolResult<ClientSettingsRuntime> settingsResult = ClientSettingsRuntime.create(
                runtime,
                configDirectory.resolve("models.json"),
                configDirectory.resolve("model.json"),
                configDirectory.resolve("model-metadata.json"),
                configDirectory.resolve("capabilities.json"),
                configDirectory.resolve("recipes.json"),
                recipeClient,
                System.getenv(),
                dispatcher,
                bridge.remoteTools(),
                clock,
                display,
                historySettings);
        ClientSettingsRuntime settings =
                settingsResult instanceof ToolResult.Success<ClientSettingsRuntime> success
                        ? success.value()
                        : null;
        ClientModelRuntimeRegistry modelRegistry =
                settings == null ? null : settings.models();
        GuideLocalEndpoint local = modelRegistry;
        MinecraftGuideContextProvider contexts = new MinecraftGuideContextProvider(
                runtime,
                Minecraft.getInstance(),
                gson,
                OpenAllayFabricClient.class.getClassLoader(),
                recipeClient);
        dev.openallay.agent.tool.ToolRuntimeCatalog fallbackClientTools =
                dev.openallay.agent.tool.ToolRuntimeCatalog.from(
                        runtime.tools().registrations(),
                        runtime.tools().descriptors().stream()
                                .map(dev.openallay.tool.ToolDescriptor::id)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        bridge.configureClientTools(
                () -> modelRegistry == null
                        ? fallbackClientTools
                        : modelRegistry.capabilities().localTools(),
                (required, correlation) -> {
                    java.util.concurrent.CompletableFuture<
                            dev.openallay.context.ToolInvocationContext> captured =
                            new java.util.concurrent.CompletableFuture<>();
                    Minecraft.getInstance().execute(() -> {
                        ToolResult<dev.openallay.context.ToolInvocationContext> result =
                                contexts.capture(required, correlation);
                        if (result instanceof ToolResult.Success<
                                dev.openallay.context.ToolInvocationContext> success) {
                            captured.complete(success.value());
                        } else {
                            ToolResult.Failure<dev.openallay.context.ToolInvocationContext> failure =
                                    (ToolResult.Failure<
                                            dev.openallay.context.ToolInvocationContext>) result;
                            captured.completeExceptionally(new IllegalStateException(
                                    failure.code() + ": " + failure.message()));
                        }
                    });
                    return captured;
                },
                gson);
        PayloadGuideRemoteEndpoint remote = new PayloadGuideRemoteEndpoint(
                new PayloadGuideRemoteEndpoint.Port() {
                    @Override public dev.openallay.bridge.protocol.CapabilityPayload capabilities() {
                        return bridge.capabilities();
                    }
                    @Override public boolean ask(
                            dev.openallay.bridge.protocol.ServerAgentRequestPayload request,
                            java.util.function.Consumer<dev.openallay.bridge.protocol.ServerAgentEventPayload> events) {
                        return bridge.askServer(request, events);
                    }
                    @Override public boolean cancel(java.util.UUID requestId) {
                        return bridge.cancelServer(requestId);
                    }
                    @Override public void disconnect() { bridge.disconnectState(); }
                },
                gson);
        GuideHistoryRepository history = new GuideHistoryRepository(new SqliteGuideHistoryStore(
                FabricLoader.getInstance().getConfigDir().resolve("openallay/history.sqlite3"),
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
        historySettings.bind(services);
        bridge.onDisconnect(services::disconnect);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client ->
                services.shutdown()
                        .handle((ignored, failure) -> null)
                        .thenCompose(ignored -> history.closeAsync())
                        .thenCompose(ignored -> settings == null
                                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                                : settings.closeAsync()));
        bridge.onCapabilitiesChanged(() -> {
            var current = services.current();
            if (current != null) current.refreshCapabilities();
        });
        java.util.function.Consumer<dev.openallay.guide.GuideService> showGuide =
                new java.util.function.Consumer<>() {
                    @Override
                    public void accept(dev.openallay.guide.GuideService service) {
                        Runnable openSettings = settings == null ? null : () ->
                                Minecraft.getInstance().gui.setScreen(new OpenAllaySettingsScreen(
                                        settings.settings(), () -> accept(service)));
                        Minecraft.getInstance().gui.setScreen(new OpenAllayScreen(
                                service,
                                recipeClient,
                                display,
                                openSettings));
                    }
                };
        dev.openallay.guide.GuideScreenOpener screens = service -> {
            showGuide.accept(service);
            return new ToolResult.Success<>(true);
        };
        FabricGuideCommands.register(new GuideCommandFacade(
                runtime,
                services,
                contexts,
                screens));
        var openGuide = KeyMappingHelper.registerKeyMapping(OpenAllayKeyMappings.OPEN_GUIDE);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuide.consumeClick()) {
                if (client.player != null) screens.open(services.forActor(client.player.getUUID()));
            }
        });
        GuideClientE2EConfig.from(System.getProperties()).ifPresent(config -> {
            String modVersion = FabricLoader.getInstance().getModContainer("openallay")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
            String secret = System.getenv("OPENALLAY_API_KEY");
            GuideClientE2EController controller = new GuideClientE2EController(
                    config,
                    "fabric",
                    runtime.platform().gameVersion(),
                    modVersion,
                    services,
                    gson,
                    () -> Minecraft.getInstance().stop(),
                    secret == null || secret.isBlank() ? java.util.Set.of() : java.util.Set.of(secret),
                    contexts::recipeProviderReadiness,
                    settings == null ? null : settings.settings());
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.player != null) controller.tick(client.player.getUUID());
            });
        });
    }
}
