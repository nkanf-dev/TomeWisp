package dev.tomewisp.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuidePersistenceSnapshot;
import dev.tomewisp.guide.GuideSessionSnapshot;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.ui.GuideDisplayConfig;
import dev.tomewisp.guide.history.GuideHistoryActivity;
import dev.tomewisp.capability.CapabilityCatalogSnapshot;
import dev.tomewisp.capability.CapabilityPolicy;
import dev.tomewisp.recipe.config.RecipeClientConfig;
import dev.tomewisp.recipe.RecipeVisibilityPolicy;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.ResolvedModelProfile;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.model.metadata.ModelMetadata;
import dev.tomewisp.model.metadata.ModelMetadataUpdate;
import dev.tomewisp.settings.model.ModelConnectionResult;
import dev.tomewisp.settings.model.ModelProfileSettingsView;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.settings.capability.RecipeSettingsView;
import dev.tomewisp.settings.diagnostics.SettingsDiagnosticsAggregator;
import dev.tomewisp.tool.ToolResult;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ClientSettingsServiceTest {
    @Test
    void initialSnapshotPreservesProfileOrderAndOnlyCredentialPresence() {
        FakeModels models = new FakeModels(state(config("alpha", "beta")));
        ClientSettingsService service = service(models, Set.of("BETA_KEY", "ALPHA_KEY"));

        ClientSettingsSnapshot snapshot = service.snapshot();

        assertEquals(0, snapshot.generation());
        assertEquals(List.of("alpha", "beta"), snapshot.models().profiles().stream()
                .map(profile -> profile.definition().id())
                .toList());
        assertTrue(snapshot.models().profiles().getFirst().credentialPresent());
        assertTrue(snapshot.models().profiles().getLast().credentialPresent());
        assertFalse(snapshot.toString().contains("secret-alpha"));
        assertEquals(SettingsOperation.Kind.IDLE, snapshot.operation().kind());
        assertEquals(4, snapshot.diagnostics().cards().size());
        assertTrue(snapshot.diagnostics().debug().isEmpty());
    }

    @Test
    void debugDisplayAddsOnlyTheSeparateTechnicalDiagnosticsProjection() {
        FakeModels models = new FakeModels(state(config("alpha")));
        ClientSettingsService service = new ClientSettingsService(
                new GuideDisplayConfig(GuideDisplayConfig.SCHEMA_VERSION, true),
                models.current,
                Set.of("ALPHA_KEY"),
                models,
                models,
                Runnable::run,
                Runnable::run);

        ClientSettingsSnapshot snapshot = service.snapshot();

        assertEquals(4, snapshot.diagnostics().cards().size());
        assertTrue(snapshot.diagnostics().debug().isPresent());
        assertFalse(snapshot.diagnostics().toString().contains("secret-alpha"));
    }

    @Test
    void probeAndMutationShareOneForegroundSlotAndCancellationIsImmediate() {
        FakeModels models = new FakeModels(state(config("alpha")));
        models.probe = new CompletableFuture<>();
        ClientSettingsService service = service(models, Set.of("ALPHA_KEY"));

        CompletableFuture<ModelConnectionResult> pending =
                service.testConnection(profile("alpha"));

        assertFailure(service.saveModels(config("beta")).join(), "settings_busy");
        assertEquals("connection_test_busy", assertInstanceOf(
                ModelConnectionResult.Failure.class,
                service.testConnection(profile("beta")).join()).code());
        assertTrue(service.cancelConnectionTest());
        assertEquals("connection_cancelled", assertInstanceOf(
                ModelConnectionResult.Failure.class, pending.join()).code());
        assertTrue(models.probeCancelled.get());
        assertEquals(SettingsOperation.Kind.IDLE, service.snapshot().operation().kind());
    }

    @Test
    void ordinaryListenerDetachDoesNotCancelConfirmedSave() throws Exception {
        ManualExecutor worker = new ManualExecutor();
        FakeModels models = new FakeModels(state(config("alpha")));
        ClientSettingsService service = service(
                models, Set.of("ALPHA_KEY", "BETA_KEY"), worker);
        List<ClientSettingsSnapshot> seen = new ArrayList<>();
        AutoCloseable listener = service.listen(seen::add);

        CompletableFuture<ToolResult<Boolean>> pending = service.saveModels(config("beta"));
        listener.close();
        worker.runNext();

        assertSuccess(pending.join());
        assertEquals("beta", service.snapshot().models().config().defaultProfileId());
        assertEquals(2, seen.size());
    }

    @Test
    void failedSaveRetainsLastValidProjection() {
        FakeModels models = new FakeModels(state(config("alpha")));
        models.saveFailure = new ToolResult.Failure<>(
                "settings_write_failed", "Unable to save settings");
        ClientSettingsService service = service(models, Set.of("ALPHA_KEY", "BETA_KEY"));

        ToolResult<Boolean> result = service.saveModels(config("beta")).join();

        assertFailure(result, "settings_write_failed");
        assertEquals("alpha", service.snapshot().models().config().defaultProfileId());
        assertEquals("settings_write_failed", service.snapshot().notice().code());
    }

    @Test
    void lateMetadataPreparationCannotOverwriteNewerProfileGeneration() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor dispatcher = new ManualExecutor();
        FakeModels models = new FakeModels(state(config("alpha")));
        ClientSettingsService service = new ClientSettingsService(
                GuideDisplayConfig.defaults(),
                models.current,
                Set.of("ALPHA_KEY", "BETA_KEY"),
                models,
                models,
                dispatcher::execute,
                worker);

        service.acceptMetadataUpdate(new ModelMetadataUpdate(Map.of(), null));
        worker.runNext(); // Prepare alpha; its completion waits in the dispatcher.
        service.saveModels(config("beta"));
        worker.runNext(); // Save beta; its completion is queued after alpha's completion.
        dispatcher.runLast(); // Deliver beta first to force the stale-generation race.
        dispatcher.runAll();
        worker.runAll();
        dispatcher.runAll();

        assertEquals("beta", service.snapshot().models().config().defaultProfileId());
        assertEquals("beta", models.publishedDefault);
        assertFalse(models.publishedDefaultsAfterSave.contains("alpha"));
    }

    @Test
    void olderMetadataUpdateCannotOverwriteNewerCacheGeneration() {
        ManualExecutor worker = new ManualExecutor();
        ManualExecutor dispatcher = new ManualExecutor();
        FakeModels models = new FakeModels(state(config("alpha")));
        ClientSettingsService service = new ClientSettingsService(
                GuideDisplayConfig.defaults(),
                models.current,
                Set.of("ALPHA_KEY"),
                models,
                models,
                dispatcher::execute,
                worker);

        service.acceptMetadataUpdate(metadata(100_000));
        worker.runNext();
        service.acceptMetadataUpdate(metadata(200_000));
        worker.runNext();
        dispatcher.runAll();
        worker.runAll();
        dispatcher.runAll();

        assertFalse(models.publishedMetadataWindows.contains(100_000));
        assertTrue(models.publishedMetadataWindows.contains(200_000));
    }

    @Test
    void closeCancelsProbeAndClosesMetadataOwner() {
        ManualExecutor worker = new ManualExecutor();
        FakeModels models = new FakeModels(state(config("alpha")));
        ClientSettingsService service = service(models, Set.of("ALPHA_KEY"), worker);
        models.probe = new CompletableFuture<>();

        CompletableFuture<ModelConnectionResult> probe =
                service.testConnection(profile("alpha"));
        service.closeAsync().join();

        assertEquals("connection_cancelled", assertInstanceOf(
                ModelConnectionResult.Failure.class, probe.join()).code());
        assertTrue(models.closed.get());
    }

    @Test
    void closeDoesNotCancelConfirmedSave() {
        ManualExecutor worker = new ManualExecutor();
        FakeModels models = new FakeModels(state(config("alpha")));
        ClientSettingsService service = service(
                models, Set.of("ALPHA_KEY", "BETA_KEY"), worker);

        CompletableFuture<ToolResult<Boolean>> save = service.saveModels(config("beta"));
        service.closeAsync().join();
        worker.runAll();

        assertSuccess(save.join());
        assertEquals("beta", service.snapshot().models().config().defaultProfileId());
    }

    @Test
    void recipeChildSaveWritesOnlyRecipeDomain() {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        ClientSettingsService service = service(models, domains, Runnable::run);
        RecipeClientConfig candidate = new RecipeClientConfig(
                RecipeClientConfig.SCHEMA_VERSION,
                RecipeVisibilityPolicy.ALL_KNOWN,
                "viewer:rei",
                Set.of("viewer:jei"));

        assertSuccess(service.saveRecipeSettings(candidate).join());

        assertEquals(1, domains.recipeSaves);
        assertEquals(0, domains.capabilitySaves);
        assertEquals(0, models.saveCalls);
        assertEquals(candidate, service.snapshot().recipes().config());
    }

    @Test
    void capabilityDependencyFailureRetainsPriorProjection() {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        domains.capabilityFailure = new ToolResult.Failure<>(
                "capability_dependency_conflict", "Skill requires a disabled Tool");
        ClientSettingsService service = service(models, domains, Runnable::run);
        CapabilitySettingsView prior = service.snapshot().capabilities();

        ToolResult<Boolean> result = service.saveCapabilities(new CapabilityPolicy(
                CapabilityPolicy.SCHEMA_VERSION, Set.of("test:fact"), Set.of())).join();

        assertFailure(result, "capability_dependency_conflict");
        assertEquals(prior, service.snapshot().capabilities());
        assertEquals(0, domains.recipeSaves);
    }

    @Test
    void capabilityAndRecipeActionsShareForegroundSlot() {
        ManualExecutor worker = new ManualExecutor();
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        ClientSettingsService service = service(models, domains, worker);

        CompletableFuture<ToolResult<Boolean>> pending = service.saveCapabilities(
                CapabilityPolicy.defaults());

        assertFailure(
                service.saveRecipeSettings(RecipeClientConfig.defaults()).join(),
                "settings_busy");
        worker.runAll();
        assertSuccess(pending.join());
        assertEquals(1, domains.capabilitySaves);
        assertEquals(0, domains.recipeSaves);
    }

    @Test
    void domainReloadRequiresDiscardConfirmationAndUpdatesOnlyThatView() {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        ClientSettingsService service = service(models, domains, Runnable::run);

        assertFailure(service.reloadCapabilities(false).join(),
                "settings_discard_confirmation_required");
        assertSuccess(service.reloadRecipeSettings(true).join());

        assertEquals(0, domains.capabilityReloads);
        assertEquals(1, domains.recipeReloads);
        assertEquals("recipes_reloaded", service.snapshot().notice().code());
    }

    @Test
    void displaySavePublishesDebugProjectionOnlyAfterBackendSuccess() {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        FakeDisplay display = new FakeDisplay(GuideDisplayConfig.defaults());
        FakeHistory history = new FakeHistory();
        ClientSettingsService service = service(
                models, domains, display, history, Runnable::run);

        assertSuccess(service.saveDisplay(new GuideDisplayConfig(1, true)).join());

        assertTrue(service.snapshot().display().debugMode());
        assertTrue(service.snapshot().diagnostics().debug().isPresent());
        assertEquals(1, display.saves);
        assertFalse(service.snapshot().toString().contains(
                history.state.guide().orElseThrow().actorId().toString()));
    }

    @Test
    void failedDisplaySaveRetainsNormalProjection() {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        FakeDisplay display = new FakeDisplay(GuideDisplayConfig.defaults());
        display.failure = new ToolResult.Failure<>(
                "settings_write_failed", "Unable to save settings");
        ClientSettingsService service = service(
                models, domains, display, new FakeHistory(), Runnable::run);

        assertFailure(service.saveDisplay(new GuideDisplayConfig(1, true)).join(),
                "settings_write_failed");

        assertFalse(service.snapshot().display().debugMode());
        assertTrue(service.snapshot().diagnostics().debug().isEmpty());
    }

    @Test
    void wholeDatabaseResetRequiresDebugModeAndFreshSecondConfirmation() {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        FakeDisplay display = new FakeDisplay(new GuideDisplayConfig(1, true));
        FakeHistory history = new FakeHistory();
        ClientSettingsService service = service(
                models, domains, display, history, Runnable::run);

        ClientSettingsService.HistoryConfirmationToken first = successValue(
                service.requestHistoryConfirmation(
                        ClientSettingsService.HistoryAction.RESET_DATABASE));
        assertFailure(service.resetHistoryDatabase(first).join(),
                "history_delete_confirmation_required");
        ClientSettingsService.HistoryConfirmationToken second = successValue(
                service.confirmHistoryReset(first));

        ClientSettingsService.HistoryConfirmationToken staleFirst = successValue(
                service.requestHistoryConfirmation(
                        ClientSettingsService.HistoryAction.RESET_DATABASE));
        assertSuccess(service.saveDisplay(new GuideDisplayConfig(1, true)).join());
        assertFailure(service.confirmHistoryReset(staleFirst),
                "history_delete_confirmation_required");
        assertFailure(service.resetHistoryDatabase(second).join(),
                "history_delete_confirmation_required");

        ClientSettingsService.HistoryConfirmationToken freshFirst = successValue(
                service.requestHistoryConfirmation(
                        ClientSettingsService.HistoryAction.RESET_DATABASE));
        ClientSettingsService.HistoryConfirmationToken freshSecond = successValue(
                service.confirmHistoryReset(freshFirst));
        assertSuccess(service.resetHistoryDatabase(freshSecond).join());
        assertFailure(service.resetHistoryDatabase(freshSecond).join(),
                "history_delete_confirmation_required");
        assertEquals(1, history.databaseResets);
    }

    @Test
    void databaseResetIsUnrequestableWhileDebugModeIsOff() {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        FakeDisplay display = new FakeDisplay(GuideDisplayConfig.defaults());
        FakeHistory history = new FakeHistory();
        ClientSettingsService service = service(
                models, domains, display, history, Runnable::run);

        assertFailure(service.requestHistoryConfirmation(
                ClientSettingsService.HistoryAction.RESET_DATABASE),
                "history_delete_confirmation_required");
        assertEquals(0, history.databaseResets);
    }

    @Test
    void historyConfirmationIsActionBoundAndOneUse() {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        FakeDisplay display = new FakeDisplay(GuideDisplayConfig.defaults());
        FakeHistory history = new FakeHistory();
        ClientSettingsService service = service(
                models, domains, display, history, Runnable::run);
        ClientSettingsService.HistoryConfirmationToken current = successValue(
                service.requestHistoryConfirmation(
                        ClientSettingsService.HistoryAction.DELETE_CURRENT));

        assertFailure(service.deleteActorHistory(current).join(),
                "history_delete_confirmation_required");
        assertSuccess(service.deleteCurrentHistory(current).join());
        assertFailure(service.deleteCurrentHistory(current).join(),
                "history_delete_confirmation_required");
        assertEquals(1, history.currentDeletes);
        assertEquals(0, history.actorDeletes);
    }

    @Test
    void historyBusyStateRejectsConfirmationWithoutCallingBackend() {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        FakeDisplay display = new FakeDisplay(GuideDisplayConfig.defaults());
        FakeHistory history = new FakeHistory();
        history.state = new ClientSettingsService.HistoryRuntimeState(
                true,
                history.state.guide(),
                new GuideHistoryActivity(1, false),
                history.state.scopeKind());
        ClientSettingsService service = service(
                models, domains, display, history, Runnable::run);

        assertFailure(service.requestHistoryConfirmation(
                ClientSettingsService.HistoryAction.DELETE_CURRENT),
                "history_delete_busy");
        assertEquals(0, history.currentDeletes);
    }

    @Test
    void listenerDetachDuringConfirmedHistoryDeleteDoesNotCancelCommit() throws Exception {
        FakeModels models = new FakeModels(state(config("alpha")));
        FakeDomains domains = new FakeDomains();
        FakeDisplay display = new FakeDisplay(GuideDisplayConfig.defaults());
        FakeHistory history = new FakeHistory();
        history.currentResult = new CompletableFuture<>();
        ClientSettingsService service = service(
                models, domains, display, history, Runnable::run);
        List<ClientSettingsSnapshot> seen = new ArrayList<>();
        AutoCloseable listener = service.listen(seen::add);
        ClientSettingsService.HistoryConfirmationToken confirmation = successValue(
                service.requestHistoryConfirmation(
                        ClientSettingsService.HistoryAction.DELETE_CURRENT));

        CompletableFuture<ToolResult<Boolean>> deleting =
                service.deleteCurrentHistory(confirmation);
        listener.close();
        history.currentResult.complete(new ToolResult.Success<>(Boolean.TRUE));

        assertSuccess(deleting.join());
        assertEquals(1, history.currentDeletes);
        assertEquals(2, seen.size());
        assertEquals("history_current_deleted", service.snapshot().notice().code());
    }

    private static ClientSettingsService service(FakeModels models, Set<String> environment) {
        return service(models, environment, Runnable::run);
    }

    private static ClientSettingsService service(
            FakeModels models, Set<String> environment, Executor worker) {
        return new ClientSettingsService(
                GuideDisplayConfig.defaults(),
                models.current,
                environment,
                models,
                models,
                Runnable::run,
                worker);
    }

    private static ClientSettingsService service(
            FakeModels models, FakeDomains domains, Executor worker) {
        return new ClientSettingsService(
                GuideDisplayConfig.defaults(),
                models.current,
                Set.of("ALPHA_KEY"),
                models,
                models,
                domains.capabilities,
                domains,
                domains.recipes,
                domains,
                Runnable::run,
                worker,
                null);
    }

    private static ClientSettingsService service(
            FakeModels models,
            FakeDomains domains,
            FakeDisplay display,
            FakeHistory history,
            Executor worker) {
        return new ClientSettingsService(
                display.current,
                display,
                models.current,
                Set.of("ALPHA_KEY"),
                models,
                models,
                domains.capabilities,
                domains,
                domains.recipes,
                domains,
                history,
                Runnable::run,
                worker,
                null);
    }

    private static ModelProfilesConfig config(String... ids) {
        return new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION,
                ids[0],
                java.util.Arrays.stream(ids).map(ClientSettingsServiceTest::profile).toList());
    }

    private static ModelProfileDefinition profile(String id) {
        return new ModelProfileDefinition(
                id,
                id.toUpperCase(),
                true,
                ModelProtocol.OPENAI_CHAT,
                URI.create("https://provider.example/v1"),
                "vendor/" + id,
                id.toUpperCase() + "_KEY",
                256_000,
                1_024,
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                null);
    }

    private static ModelMetadataUpdate metadata(int contextWindow) {
        ModelMetadata value = new ModelMetadata(
                "openrouter",
                "vendor/alpha",
                "vendor/alpha",
                contextWindow,
                4_096,
                Instant.EPOCH);
        return new ModelMetadataUpdate(Map.of(value.key(), value), null);
    }

    private static ClientSettingsService.ModelState state(ModelProfilesConfig config) {
        return new ClientSettingsService.ModelState(
                config,
                config.profiles().stream()
                        .map(ClientSettingsServiceTest::resolved)
                        .map(ModelProfileSettingsView.Resolution::from)
                        .toList());
    }

    private static ResolvedModelProfile resolved(ModelProfileDefinition definition) {
        return new ResolvedModelProfile(
                definition,
                new ModelConfig(
                        true,
                        definition.protocol(),
                        definition.baseUri(),
                        definition.model(),
                        SecretValue.of("secret-" + definition.id()),
                        definition.contextWindowTokens(),
                        definition.maxOutputTokens(),
                        definition.connectTimeout(),
                        definition.requestTimeout()),
                null);
    }

    private static void assertSuccess(ToolResult<Boolean> result) {
        assertInstanceOf(ToolResult.Success.class, result);
    }

    private static void assertFailure(ToolResult<?> result, String code) {
        assertEquals(code, assertInstanceOf(ToolResult.Failure.class, result).code());
    }

    private static <T> T successValue(ToolResult<T> result) {
        assertInstanceOf(ToolResult.Success.class, result);
        return ((ToolResult.Success<T>) result).value();
    }

    private static final class FakeModels
            implements ClientSettingsService.ModelActions,
                    ClientSettingsService.MetadataActions {
        private ClientSettingsService.ModelState current;
        private ToolResult.Failure<ClientSettingsService.ModelState> saveFailure;
        private CompletableFuture<ModelConnectionResult> probe = CompletableFuture.completedFuture(
                new ModelConnectionResult.Success(
                        "alpha", ModelProtocol.OPENAI_CHAT, "https://provider.example",
                        Instant.EPOCH, 1));
        private final AtomicBoolean probeCancelled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private String publishedDefault;
        private int saveCalls;
        private boolean saved;
        private final List<String> publishedDefaultsAfterSave = new ArrayList<>();
        private final List<Integer> publishedMetadataWindows = new ArrayList<>();

        private FakeModels(ClientSettingsService.ModelState current) {
            this.current = current;
            this.publishedDefault = current.config().defaultProfileId();
        }

        @Override
        public ToolResult<ClientSettingsService.ModelState> save(
                ModelProfilesConfig candidate,
                Map<ModelMetadata.Key, ModelMetadata> metadata) {
            saveCalls++;
            if (saveFailure != null) {
                return saveFailure;
            }
            current = state(candidate);
            publishedDefault = candidate.defaultProfileId();
            saved = true;
            return new ToolResult.Success<>(current);
        }

        @Override
        public ToolResult<ClientSettingsService.ModelState> reload(
                Map<ModelMetadata.Key, ModelMetadata> metadata) {
            return new ToolResult.Success<>(current);
        }

        @Override
        public ToolResult<ResolvedModelProfile> resolve(
                ModelProfileDefinition candidate,
                Map<ModelMetadata.Key, ModelMetadata> metadata) {
            return new ToolResult.Success<>(resolved(candidate));
        }

        @Override
        public ToolResult<ClientSettingsService.PreparedModels> prepare(
                ModelProfilesConfig candidate,
                Map<ModelMetadata.Key, ModelMetadata> metadata) {
            ClientSettingsService.ModelState prepared = state(candidate);
            Integer metadataWindow = metadata.values().stream()
                    .findFirst()
                    .map(ModelMetadata::contextWindowTokens)
                    .orElse(null);
            return new ToolResult.Success<>(new ClientSettingsService.PreparedModels(
                    prepared,
                    () -> {
                        publishedDefault = candidate.defaultProfileId();
                        if (saved) {
                            publishedDefaultsAfterSave.add(publishedDefault);
                        }
                        if (metadataWindow != null) {
                            publishedMetadataWindows.add(metadataWindow);
                        }
                    }));
        }

        @Override
        public CompletableFuture<ModelConnectionResult> probe(
                ResolvedModelProfile profile, CancellationSignal cancellation) {
            cancellation.onCancel(() -> probeCancelled.set(true));
            return probe;
        }

        @Override
        public CompletableFuture<Void> refresh() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            closed.set(true);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FakeDomains
            implements ClientSettingsService.CapabilityActions,
                    ClientSettingsService.RecipeActions {
        private CapabilitySettingsView capabilities = new CapabilitySettingsView(
                CapabilityPolicy.defaults(),
                new CapabilityCatalogSnapshot(List.of()),
                Set.of(),
                Set.of());
        private RecipeSettingsView recipes = RecipeSettingsView.defaults();
        private ToolResult.Failure<CapabilitySettingsView> capabilityFailure;
        private int capabilitySaves;
        private int capabilityReloads;
        private int recipeSaves;
        private int recipeReloads;

        @Override
        public ToolResult<CapabilitySettingsView> saveCapabilities(CapabilityPolicy candidate) {
            capabilitySaves++;
            if (capabilityFailure != null) {
                return capabilityFailure;
            }
            capabilities = new CapabilitySettingsView(
                    candidate, capabilities.catalog(), Set.of(), Set.of());
            return new ToolResult.Success<>(capabilities);
        }

        @Override
        public ToolResult<CapabilitySettingsView> reloadCapabilities() {
            capabilityReloads++;
            return new ToolResult.Success<>(capabilities);
        }

        @Override
        public ToolResult<RecipeSettingsView> saveRecipes(RecipeClientConfig candidate) {
            recipeSaves++;
            recipes = new RecipeSettingsView(candidate, List.of(), Set.of(), false);
            return new ToolResult.Success<>(recipes);
        }

        @Override
        public ToolResult<RecipeSettingsView> reloadRecipes() {
            recipeReloads++;
            return new ToolResult.Success<>(recipes);
        }
    }

    private static final class FakeDisplay implements ClientSettingsService.DisplayActions {
        private GuideDisplayConfig current;
        private ToolResult.Failure<GuideDisplayConfig> failure;
        private int saves;

        private FakeDisplay(GuideDisplayConfig current) {
            this.current = current;
        }

        @Override
        public ToolResult<GuideDisplayConfig> saveDisplay(GuideDisplayConfig candidate) {
            saves++;
            if (failure != null) {
                return failure;
            }
            current = candidate;
            return new ToolResult.Success<>(candidate);
        }

        @Override
        public ToolResult<GuideDisplayConfig> reloadDisplay() {
            return new ToolResult.Success<>(current);
        }
    }

    private static final class FakeHistory implements ClientSettingsService.HistoryActions {
        private ClientSettingsService.HistoryRuntimeState state = availableHistory();
        private CompletableFuture<ToolResult<Boolean>> currentResult =
                CompletableFuture.completedFuture(new ToolResult.Success<>(Boolean.TRUE));
        private int currentDeletes;
        private int actorDeletes;
        private int databaseResets;

        @Override
        public ClientSettingsService.HistoryRuntimeState state() {
            return state;
        }

        @Override
        public CompletableFuture<ToolResult<Boolean>> deleteCurrentHistory() {
            currentDeletes++;
            return currentResult;
        }

        @Override
        public CompletableFuture<ToolResult<Boolean>> deleteActorHistory() {
            actorDeletes++;
            return CompletableFuture.completedFuture(new ToolResult.Success<>(Boolean.TRUE));
        }

        @Override
        public CompletableFuture<ToolResult<Boolean>> resetHistoryDatabase() {
            databaseResets++;
            return CompletableFuture.completedFuture(new ToolResult.Success<>(Boolean.TRUE));
        }

        private static ClientSettingsService.HistoryRuntimeState availableHistory() {
            GuideSnapshot guide = new GuideSnapshot(
                    UUID.fromString("21876009-3d3e-4092-952a-dd7212046fe8"),
                    "main",
                    GuideModelMode.CLIENT,
                    true,
                    false,
                    GuidePersistenceSnapshot.available(0),
                    List.of(new GuideSessionSnapshot(
                            "main", List.<GuideMessage>of(), List.of())),
                    Instant.EPOCH);
            return new ClientSettingsService.HistoryRuntimeState(
                    true,
                    Optional.of(guide),
                    GuideHistoryActivity.idle(),
                    SettingsDiagnosticsAggregator.HistoryScopeKind.MULTIPLAYER_SERVER);
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }

        private void runLast() {
            tasks.removeLast().run();
        }
    }
}
