package dev.openallay.neoforge;

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
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.bus.api.IEventBus;
import dev.openallay.neoforge.network.NeoForgeClientBridge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStartedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class OpenAllayNeoForgeClient {
    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private OpenAllayNeoForgeClient() {}

    public static void initialize(OpenAllayRuntime runtime, IEventBus modBus) {
        if (!REGISTERED.compareAndSet(false, true)) return;
        NeoForgeClientBridge bridge = new NeoForgeClientBridge();
        bridge.register(modBus);
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.registerCategory(OpenAllayKeyMappings.CATEGORY);
            event.register(OpenAllayKeyMappings.OPEN_GUIDE);
        });
        NeoForge.EVENT_BUS.addListener((ClientStartedEvent event) ->
                start(runtime, bridge, event.getClient()));
    }

    private static void start(
            OpenAllayRuntime runtime,
            NeoForgeClientBridge bridge,
            Minecraft client) {
        if (!STARTED.compareAndSet(false, true)) return;
        Gson gson = new Gson();
        java.time.Clock clock = java.time.Clock.systemUTC();
        var dispatcher = (dev.openallay.client.ClientEventDispatcher)
                client::execute;
        java.nio.file.Path configDirectory = FMLPaths.CONFIGDIR.get().resolve("openallay");
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
                client,
                gson,
                OpenAllayNeoForgeClient.class.getClassLoader(),
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
                    client.execute(() -> {
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
                gson,
                runtime.resources());
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
                FMLPaths.CONFIGDIR.get().resolve("openallay/history.sqlite3"),
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
                new MinecraftGuideHistoryScope(client));
        historySettings.bind(services);
        bridge.onDisconnect(services::disconnect);
        NeoForge.EVENT_BUS.addListener((ClientStoppingEvent event) ->
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
                                client.gui.setScreen(new OpenAllaySettingsScreen(
                                        settings.settings(), () -> accept(service)));
                        client.gui.setScreen(new OpenAllayScreen(
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
        NeoForgeGuideCommands.register(new GuideCommandFacade(
                runtime,
                services,
                contexts,
                screens));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            while (OpenAllayKeyMappings.OPEN_GUIDE.consumeClick()) {
                if (client.player != null) screens.open(services.forActor(client.player.getUUID()));
            }
        });
        GuideClientE2EConfig.from(System.getProperties()).ifPresent(config -> {
            String modVersion = ModList.get().getModContainerById("openallay")
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
            String secret = System.getenv("OPENALLAY_API_KEY");
            GuideClientE2EController controller = new GuideClientE2EController(
                    config,
                    "neoforge",
                    runtime.platform().gameVersion(),
                    modVersion,
                    services,
                    gson,
                    client::stop,
                    secret == null || secret.isBlank() ? java.util.Set.of() : java.util.Set.of(secret),
                    contexts::recipeProviderReadiness,
                    settings == null ? null : settings.settings());
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
                if (client.player != null) controller.tick(client.player.getUUID());
            });
        });
    }
}
