package dev.tomewisp.settings;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.client.ClientEventDispatcher;
import dev.tomewisp.client.ClientModelRuntimeRegistry;
import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.ui.GuideDisplayConfig;
import dev.tomewisp.guide.ui.GuideDisplayRuntime;
import dev.tomewisp.model.ProviderModelClients;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ModelProfilesConfigLoader;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.ResolvedModelProfile;
import dev.tomewisp.model.config.CredentialReference;
import dev.tomewisp.model.config.CredentialResolver;
import dev.tomewisp.model.config.LocalCredentialStore;
import dev.tomewisp.model.metadata.ModelMetadataBootstrap;
import dev.tomewisp.model.metadata.ModelMetadataCache;
import dev.tomewisp.model.metadata.ModelMetadataUpdate;
import dev.tomewisp.settings.model.ModelConnectionProbe;
import dev.tomewisp.settings.model.ModelProfileSettingsView;
import dev.tomewisp.settings.model.ModelSettingsBackend;
import dev.tomewisp.recipe.config.RecipeClientRuntime;
import dev.tomewisp.settings.capability.CapabilitySettingsBackend;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.settings.capability.RecipeSettingsBackend;
import dev.tomewisp.settings.capability.RecipeSettingsView;
import dev.tomewisp.tool.ToolResult;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Loader-neutral lifecycle bundle for the model registry and native settings owner. */
public record ClientSettingsRuntime(
        ClientModelRuntimeRegistry models,
        ClientSettingsService settings) {
    public ClientSettingsRuntime {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(settings, "settings");
    }

    public static ToolResult<ClientSettingsRuntime> create(
            TomeWispRuntime product,
            Path profilesPath,
            Path legacyPath,
            Path metadataCachePath,
            Map<String, String> environment,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            Clock clock,
            GuideDisplayConfig display) {
        Path configDirectory = profilesPath.toAbsolutePath().normalize().getParent();
        if (configDirectory == null) {
            return new ToolResult.Failure<>(
                    "settings_unavailable", "Native settings are unavailable");
        }
        Path recipesPath = configDirectory.resolve("recipes.json");
        return create(
                product,
                profilesPath,
                legacyPath,
                metadataCachePath,
                configDirectory.resolve("capabilities.json"),
                recipesPath,
                new RecipeClientRuntime(recipesPath),
                environment,
                dispatcher,
                extension,
                clock,
                display);
    }

    public static ToolResult<ClientSettingsRuntime> create(
            TomeWispRuntime product,
            Path profilesPath,
            Path legacyPath,
            Path metadataCachePath,
            Path capabilitiesPath,
            Path recipesPath,
            RecipeClientRuntime recipeRuntime,
            Map<String, String> environment,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            Clock clock,
            GuideDisplayConfig display) {
        return createInternal(
                product,
                profilesPath,
                legacyPath,
                metadataCachePath,
                capabilitiesPath,
                recipesPath,
                recipeRuntime,
                environment,
                dispatcher,
                extension,
                clock,
                display,
                unavailableDisplayActions(),
                new ClientSettingsHistoryBinding(),
                null);
    }

    public static ToolResult<ClientSettingsRuntime> create(
            TomeWispRuntime product,
            Path profilesPath,
            Path legacyPath,
            Path metadataCachePath,
            Path capabilitiesPath,
            Path recipesPath,
            RecipeClientRuntime recipeRuntime,
            Map<String, String> environment,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            Clock clock,
            GuideDisplayRuntime display,
            ClientSettingsService.HistoryActions historyActions) {
        Objects.requireNonNull(display, "display");
        ClientSettingsService.DisplayActions displayActions =
                new ClientSettingsService.DisplayActions() {
                    @Override
                    public ToolResult<GuideDisplayConfig> saveDisplay(
                            GuideDisplayConfig candidate) {
                        return display.save(candidate);
                    }

                    @Override
                    public ToolResult<GuideDisplayConfig> reloadDisplay() {
                        return display.reload();
                    }
                };
        return createInternal(
                product,
                profilesPath,
                legacyPath,
                metadataCachePath,
                capabilitiesPath,
                recipesPath,
                recipeRuntime,
                environment,
                dispatcher,
                extension,
                clock,
                display.config(),
                displayActions,
                historyActions,
                display.failure());
    }

    private static ToolResult<ClientSettingsRuntime> createInternal(
            TomeWispRuntime product,
            Path profilesPath,
            Path legacyPath,
            Path metadataCachePath,
            Path capabilitiesPath,
            Path recipesPath,
            RecipeClientRuntime recipeRuntime,
            Map<String, String> environment,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            Clock clock,
            GuideDisplayConfig display,
            ClientSettingsService.DisplayActions displayActions,
            ClientSettingsService.HistoryActions historyActions,
            GuideFailure displayFailure) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(display, "display");
        Objects.requireNonNull(capabilitiesPath, "capabilitiesPath");
        Objects.requireNonNull(recipesPath, "recipesPath");
        Objects.requireNonNull(recipeRuntime, "recipeRuntime");
        Objects.requireNonNull(displayActions, "displayActions");
        Objects.requireNonNull(historyActions, "historyActions");

        Map<String, String> environmentSnapshot = Map.copyOf(environment);
        LocalCredentialStore credentialStore = new LocalCredentialStore(
                profilesPath.toAbsolutePath().normalize().resolveSibling("credentials.sqlite3"),
                clock);
        CredentialResolver credentials = CredentialResolver.composite(
                credentialStore, environmentSnapshot);
        ToolResult<ModelProfilesConfigLoader.Load> loaded = new ModelProfilesConfigLoader()
                .load(
                        profilesPath,
                        legacyPath,
                        credentials,
                        environmentSnapshot,
                        Map.of());
        ModelProfilesConfigLoader.Load initial;
        SettingsNotice startupNotice = null;
        if (loaded instanceof ToolResult.Success<ModelProfilesConfigLoader.Load> success) {
            initial = success.value();
        } else {
            ToolResult.Failure<ModelProfilesConfigLoader.Load> failure =
                    (ToolResult.Failure<ModelProfilesConfigLoader.Load>) loaded;
            initial = unconfigured();
            startupNotice = SettingsNotice.failure(failure.code(), failure.message());
        }
        if (startupNotice == null && displayFailure != null) {
            startupNotice = SettingsNotice.failure(
                    displayFailure.code(), "Display settings are invalid");
        }

        try {
            Gson gson = new Gson();
            ClientModelRuntimeRegistry registry = ClientModelRuntimeRegistry.create(
                    product, initial, gson, dispatcher, extension);
            CapabilitySettingsBackend capabilities = new CapabilitySettingsBackend(
                    capabilitiesPath, product, registry);
            ToolResult<CapabilitySettingsView> loadedCapabilities =
                    capabilities.reloadCapabilities();
            CapabilitySettingsView initialCapabilities;
            if (loadedCapabilities instanceof ToolResult.Success<CapabilitySettingsView> success) {
                initialCapabilities = success.value();
            } else {
                ToolResult.Failure<CapabilitySettingsView> failure =
                        (ToolResult.Failure<CapabilitySettingsView>) loadedCapabilities;
                initialCapabilities = capabilities.currentView();
                if (startupNotice == null) {
                    startupNotice = SettingsNotice.failure(failure.code(), failure.message());
                }
            }
            RecipeSettingsBackend recipes = new RecipeSettingsBackend(recipesPath, recipeRuntime);
            RecipeSettingsView initialRecipes = recipes.currentView();
            ModelConnectionProbe probe = new ModelConnectionProbe(
                    config -> ProviderModelClients.create(config, gson),
                    clock,
                    System::nanoTime);
            ModelSettingsBackend backend = new ModelSettingsBackend(
                    profilesPath,
                    legacyPath,
                    () -> environmentSnapshot,
                    registry,
                    probe,
                    credentialStore);
            backend.collectUnreferencedCredentials(initial.config());
            AtomicReference<ClientSettingsService> serviceReference = new AtomicReference<>();
            AtomicReference<ModelMetadataUpdate> pendingUpdate = new AtomicReference<>();
            ModelMetadataBootstrap metadata = new ModelMetadataBootstrap(
                    new ModelMetadataCache(metadataCachePath),
                    profilesPath,
                    legacyPath,
                    environmentSnapshot,
                    credentials,
                    update -> {
                        ClientSettingsService service = serviceReference.get();
                        if (service == null) {
                            pendingUpdate.set(update);
                        } else {
                            service.acceptMetadataUpdate(update);
                        }
                    },
                    clock);
            ClientSettingsService.MetadataActions metadataActions =
                    new ClientSettingsService.MetadataActions() {
                        @Override
                        public CompletableFuture<Void> refresh() {
                            return metadata.refreshAll();
                        }

                        @Override
                        public CompletableFuture<Void> closeAsync() {
                            return metadata.closeAsync().whenComplete(
                                    (ignored, failure) -> backend.closeCredentials());
                        }
                    };
            ClientSettingsService.ModelState initialState = new ClientSettingsService.ModelState(
                    initial.config(),
                    initial.profiles().stream()
                            .map(ModelProfileSettingsView.Resolution::from)
                            .toList());
            ClientSettingsService service = new ClientSettingsService(
                    display,
                    displayActions,
                    initialState,
                    backend.presentEnvironmentNames(),
                    backend,
                    metadataActions,
                    initialCapabilities,
                    capabilities,
                    initialRecipes,
                    recipes,
                    historyActions,
                    dispatcher,
                    command -> Thread.startVirtualThread(command),
                    startupNotice);
            serviceReference.set(service);
            ModelMetadataUpdate early = pendingUpdate.getAndSet(null);
            if (early != null) {
                service.acceptMetadataUpdate(early);
            }
            metadata.start();
            return new ToolResult.Success<>(new ClientSettingsRuntime(registry, service));
        } catch (RuntimeException failure) {
            credentialStore.close();
            return new ToolResult.Failure<>(
                    "settings_unavailable", "Native settings are unavailable");
        }
    }

    public CompletableFuture<Void> closeAsync() {
        return settings.closeAsync();
    }

    private static ClientSettingsService.DisplayActions unavailableDisplayActions() {
        return new ClientSettingsService.DisplayActions() {
            @Override
            public ToolResult<GuideDisplayConfig> saveDisplay(GuideDisplayConfig candidate) {
                return new ToolResult.Failure<>(
                        "display_settings_unavailable", "Display settings are unavailable");
            }

            @Override
            public ToolResult<GuideDisplayConfig> reloadDisplay() {
                return new ToolResult.Failure<>(
                        "display_settings_unavailable", "Display settings are unavailable");
            }
        };
    }

    private static ModelProfilesConfigLoader.Load unconfigured() {
        ModelProfileDefinition definition = new ModelProfileDefinition(
                "default",
                "Configure a model",
                false,
                ModelProtocol.OPENAI_CHAT,
                URI.create("https://example.invalid/v1"),
                "configure-model-id",
                CredentialReference.environment("TOMEWISP_API_KEY").encoded(),
                256_000,
                4_096,
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                null);
        ModelProfilesConfig config = new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION,
                definition.id(),
                List.of(definition));
        ResolvedModelProfile resolved = new ResolvedModelProfile(
                definition,
                null,
                new GuideFailure("model_disabled", "This model profile is disabled"));
        return new ModelProfilesConfigLoader.Load(
                config, List.of(resolved), false);
    }
}
