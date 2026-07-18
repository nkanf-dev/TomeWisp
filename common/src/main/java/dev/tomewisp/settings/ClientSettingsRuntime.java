package dev.tomewisp.settings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import dev.tomewisp.recipe.config.RecipeClientConfig;
import dev.tomewisp.settings.capability.CapabilitySettingsBackend;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.settings.capability.RecipeSettingsBackend;
import dev.tomewisp.settings.capability.RecipeSettingsView;
import dev.tomewisp.settings.skill.SkillSettingsBackend;
import dev.tomewisp.settings.tool.ToolSettingsBackend;
import dev.tomewisp.tool.config.ToolConfigException;
import dev.tomewisp.tool.config.ToolFamilyConfig;
import dev.tomewisp.tool.config.ToolFamilyId;
import dev.tomewisp.tool.config.ToolFamilySettingsStore;
import dev.tomewisp.tool.config.ToolSourceDefinition;
import dev.tomewisp.tool.config.ToolSourceKind;
import dev.tomewisp.tool.config.ToolSourceKindRegistry;
import dev.tomewisp.tool.config.LocalMarkdownKnowledgeProvider;
import dev.tomewisp.knowledge.KnowledgeSourceProvider;
import dev.tomewisp.tool.ToolResult;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        Path recipesPath = configDirectory.resolve("tools").resolve("recipes-options.json");
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
            Path configDirectory = profilesPath.toAbsolutePath().normalize().getParent();
            if (configDirectory == null) {
                throw new IllegalArgumentException("Model profiles require a configuration directory");
            }
            Set<String> installedSkillMods = product.platform().isModLoaded("ftbquests")
                    ? Set.of("ftbquests")
                    : Set.of();
            SkillSettingsBackend skills = new SkillSettingsBackend(
                    configDirectory.resolve("skills"), product.skills(), installedSkillMods);
            ClientModelRuntimeRegistry registry = ClientModelRuntimeRegistry.create(
                    product, initial, gson, dispatcher, extension);
            CapabilitySettingsBackend capabilities = new CapabilitySettingsBackend(
                    capabilitiesPath, product, registry);
            RecipeSettingsBackend recipes = new RecipeSettingsBackend(recipesPath, recipeRuntime);
            RecipeSettingsView initialRecipes = recipes.currentView();
            ToolSourceKindRegistry sourceKinds = sourceKinds();
            EnumMap<ToolFamilyId, ToolFamilySettingsStore> toolStores = toolStores(
                    configDirectory.resolve("tools"), sourceKinds, initialRecipes);
            for (ToolFamilySettingsStore store : toolStores.values()) {
                ToolResult<ToolFamilyConfig> loadedTool = store.load();
                if (startupNotice == null
                        && loadedTool instanceof ToolResult.Failure<ToolFamilyConfig> failure) {
                    startupNotice = SettingsNotice.failure(failure.code(), failure.message());
                }
            }
            ToolSettingsBackend toolSettings = new ToolSettingsBackend(
                    toolStores, sourceKinds, capabilities::currentView, recipes::currentView);
            for (ToolFamilyId family : List.of(ToolFamilyId.RECIPES, ToolFamilyId.GUIDES)) {
                ToolResult<Boolean> applied = applyToolRuntime(
                        toolSettings.current(family), recipes, product, configDirectory);
                if (startupNotice == null && applied instanceof ToolResult.Failure<Boolean> failure) {
                    startupNotice = SettingsNotice.failure(failure.code(), failure.message());
                }
            }
            ToolSettingsBackend.State initialToolState = toolSettings.currentState();
            ToolResult<CapabilitySettingsView> publishedCapabilities =
                    capabilities.publishCapabilities(initialToolState.capabilityPolicy());
            CapabilitySettingsView initialCapabilities = publishedCapabilities
                    instanceof ToolResult.Success<CapabilitySettingsView> success
                            ? success.value()
                            : capabilities.currentView();
            initialToolState = new ToolSettingsBackend.State(
                    toolSettings.currentView(), initialCapabilities.policy());
            ClientSettingsService.ToolActions toolActions = new ClientSettingsService.ToolActions() {
                @Override
                public ToolResult<ToolSettingsBackend.State> save(ToolFamilyConfig candidate) {
                    ToolFamilyConfig prior = toolSettings.current(candidate.toolId());
                    ToolResult<ToolSettingsBackend.State> saved = toolSettings.save(candidate);
                    if (saved instanceof ToolResult.Failure<ToolSettingsBackend.State> failure) {
                        return failure;
                    }
                    ToolResult<Boolean> applied =
                            applyToolRuntime(candidate, recipes, product, configDirectory);
                    if (applied instanceof ToolResult.Failure<Boolean> failure) {
                        rollbackToolRuntime(toolSettings, prior, recipes, product, configDirectory);
                        return new ToolResult.Failure<>(failure.code(), failure.message());
                    }
                    ToolSettingsBackend.State state = toolSettings.currentState();
                    ToolResult<CapabilitySettingsView> published =
                            capabilities.publishCapabilities(state.capabilityPolicy());
                    if (published instanceof ToolResult.Failure<CapabilitySettingsView> failure) {
                        rollbackToolRuntime(toolSettings, prior, recipes, product, configDirectory);
                        return new ToolResult.Failure<>(failure.code(), failure.message());
                    }
                    CapabilitySettingsView capabilityView =
                            ((ToolResult.Success<CapabilitySettingsView>) published).value();
                    return new ToolResult.Success<>(new ToolSettingsBackend.State(
                            toolSettings.currentView(), capabilityView.policy()));
                }

                @Override
                public ToolResult<ToolSettingsBackend.State> reload(ToolFamilyId family) {
                    ToolFamilyConfig prior = toolSettings.current(family);
                    ToolResult<ToolSettingsBackend.State> loaded = toolSettings.reload(family);
                    if (loaded instanceof ToolResult.Failure<ToolSettingsBackend.State> failure) {
                        return failure;
                    }
                    ToolFamilyConfig candidate = toolSettings.current(family);
                    ToolResult<Boolean> applied =
                            applyToolRuntime(candidate, recipes, product, configDirectory);
                    if (applied instanceof ToolResult.Failure<Boolean> failure) {
                        rollbackToolRuntime(toolSettings, prior, recipes, product, configDirectory);
                        return new ToolResult.Failure<>(failure.code(), failure.message());
                    }
                    ToolSettingsBackend.State state = toolSettings.currentState();
                    ToolResult<CapabilitySettingsView> published =
                            capabilities.publishCapabilities(state.capabilityPolicy());
                    if (published instanceof ToolResult.Failure<CapabilitySettingsView> failure) {
                        rollbackToolRuntime(toolSettings, prior, recipes, product, configDirectory);
                        return new ToolResult.Failure<>(failure.code(), failure.message());
                    }
                    CapabilitySettingsView capabilityView =
                            ((ToolResult.Success<CapabilitySettingsView>) published).value();
                    return new ToolResult.Success<>(new ToolSettingsBackend.State(
                            toolSettings.currentView(), capabilityView.policy()));
                }
            };
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
                    initialToolState.view(),
                    toolActions,
                    skills.currentView(),
                    skills,
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

    private static ToolSourceKindRegistry sourceKinds() {
        return ToolSourceKindRegistry.builder()
                .register(emptySourceKind("recipe_catalog", ToolFamilyId.RECIPES))
                .register(emptySourceKind("recipe_viewer", ToolFamilyId.RECIPES))
                .register(emptySourceKind("patchouli_books", ToolFamilyId.GUIDES))
                .register(emptySourceKind("ftb_quests", ToolFamilyId.GUIDES))
                .register(ToolSourceKind.localMarkdown())
                .build();
    }

    private static ToolSourceKind emptySourceKind(String kind, ToolFamilyId owner) {
        return new ToolSourceKind(
                kind,
                owner,
                Set.of(ToolSourceDefinition.Lifecycle.BUILT_IN),
                false,
                List.of(),
                config -> {
                    if (!config.keySet().isEmpty()) {
                        throw new ToolConfigException(
                                "invalid_source_config", kind + " config must be empty");
                    }
                    return new JsonObject();
                });
    }

    private static EnumMap<ToolFamilyId, ToolFamilySettingsStore> toolStores(
            Path toolsDirectory,
            ToolSourceKindRegistry registry,
            RecipeSettingsView recipes) {
        EnumMap<ToolFamilyId, ToolFamilySettingsStore> stores =
                new EnumMap<>(ToolFamilyId.class);
        for (ToolFamilyId family : ToolFamilyId.values()) {
            ToolFamilyConfig defaults = switch (family) {
                case RECIPES -> new ToolFamilyConfig(
                        ToolFamilyConfig.SCHEMA_VERSION,
                        family,
                        true,
                        recipeSources(recipes));
                case GUIDES -> new ToolFamilyConfig(
                        ToolFamilyConfig.SCHEMA_VERSION,
                        family,
                        true,
                        List.of(
                                builtInSource(
                                        "tomewisp:patchouli",
                                        "patchouli_books",
                                        "Patchouli books",
                                        true),
                                builtInSource(
                                        "tomewisp:ftbquests",
                                        "ftb_quests",
                                        "FTB Quests",
                                        true)));
                default -> ToolFamilyConfig.empty(family);
            };
            stores.put(
                    family,
                    new ToolFamilySettingsStore(toolsDirectory, family, registry, defaults));
        }
        return stores;
    }

    private static List<ToolSourceDefinition> recipeSources(RecipeSettingsView recipes) {
        List<ToolSourceDefinition> sources = new ArrayList<>();
        for (RecipeSettingsView.Source source : recipes.sources()) {
            sources.add(builtInSource(
                    source.id(),
                    source.viewer() ? "recipe_viewer" : "recipe_catalog",
                    recipeSourceName(source.id()),
                    source.enabled()));
        }
        return List.copyOf(sources);
    }

    private static ToolSourceDefinition builtInSource(
            String id, String kind, String displayName, boolean enabled) {
        return new ToolSourceDefinition(
                id,
                kind,
                displayName,
                enabled,
                new JsonObject(),
                ToolSourceDefinition.Lifecycle.BUILT_IN);
    }

    private static String recipeSourceName(String sourceId) {
        return switch (sourceId) {
            case "minecraft:client_recipe_book" -> "Minecraft recipes";
            case "viewer:jei" -> "JEI";
            case "viewer:rei" -> "REI";
            case "viewer:emi" -> "EMI";
            default -> sourceId;
        };
    }

    private static ToolResult<Boolean> applyToolRuntime(
            ToolFamilyConfig candidate,
            RecipeSettingsBackend recipes,
            TomeWispRuntime product,
            Path configDirectory) {
        if (candidate.toolId() == ToolFamilyId.RECIPES) {
            RecipeClientConfig current = recipes.currentView().config();
            Set<String> known = candidate.sources().stream()
                    .map(ToolSourceDefinition::sourceId)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.TreeSet<String> disabled = new java.util.TreeSet<>(current.disabledSources());
            disabled.removeAll(known);
            candidate.sources().stream()
                    .filter(source -> !source.enabled())
                    .map(ToolSourceDefinition::sourceId)
                    .forEach(disabled::add);
            if (disabled.equals(current.disabledSources())) {
                return new ToolResult.Success<>(Boolean.TRUE);
            }
            ToolResult<RecipeSettingsView> saved = recipes.saveRecipes(new RecipeClientConfig(
                    RecipeClientConfig.SCHEMA_VERSION,
                    current.visibility(),
                    current.preferredViewer(),
                    disabled));
            if (saved instanceof ToolResult.Failure<RecipeSettingsView> failure) {
                return new ToolResult.Failure<>(failure.code(), failure.message());
            }
            return new ToolResult.Success<>(Boolean.TRUE);
        }
        if (candidate.toolId() != ToolFamilyId.GUIDES) {
            return new ToolResult.Success<>(Boolean.TRUE);
        }

        Path managedRoot = configDirectory.resolve("knowledge").toAbsolutePath().normalize();
        List<KnowledgeSourceProvider> supplemental = new ArrayList<>();
        Set<String> disabledPrimary = new java.util.TreeSet<>();
        try {
            for (ToolSourceDefinition source : candidate.sources()) {
                if (source.sourceKind().equals("patchouli_books")) {
                    if (!source.enabled()) {
                        disabledPrimary.add("patchouli");
                    }
                    continue;
                }
                if (source.sourceKind().equals("ftb_quests")) {
                    if (!source.enabled()) {
                        disabledPrimary.add("ftbquests");
                    }
                    continue;
                }
                if (!source.enabled() || !source.sourceKind().equals("local_markdown")) {
                    continue;
                }
                JsonObject config = LocalMarkdownKnowledgeProvider.validateConfig(source.config());
                Files.createDirectories(managedRoot);
                Files.createDirectories(managedRoot.resolve(config.get("directory").getAsString()));
                supplemental.add(new LocalMarkdownKnowledgeProvider(
                        source,
                        managedRoot,
                        product.platform().gameVersion(),
                        product.platform().platformName()));
            }
            if (!product.knowledge().replaceProviderConfiguration(disabledPrimary, supplemental)) {
                return new ToolResult.Failure<>(
                        "knowledge_source_reload_failed",
                        "Unable to publish Guide sources");
            }
            return new ToolResult.Success<>(Boolean.TRUE);
        } catch (Exception failure) {
            return new ToolResult.Failure<>(
                    "knowledge_source_reload_failed",
                    "Unable to publish Guide sources");
        }
    }

    private static void rollbackToolRuntime(
            ToolSettingsBackend toolSettings,
            ToolFamilyConfig prior,
            RecipeSettingsBackend recipes,
            TomeWispRuntime product,
            Path configDirectory) {
        toolSettings.save(prior);
        applyToolRuntime(prior, recipes, product, configDirectory);
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
