package dev.tomewisp.settings;

import dev.tomewisp.capability.CapabilityPolicy;
import dev.tomewisp.client.ClientEventDispatcher;
import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuidePersistenceSnapshot;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.history.GuideHistoryActivity;
import dev.tomewisp.guide.history.GuideHistoryPartition;
import dev.tomewisp.guide.ui.GuideDisplayConfig;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ResolvedModelProfile;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.model.metadata.ModelMetadata;
import dev.tomewisp.model.metadata.ModelMetadataUpdate;
import dev.tomewisp.settings.model.ModelConnectionResult;
import dev.tomewisp.settings.model.ModelProfileSettingsView;
import dev.tomewisp.settings.capability.CapabilitySettingsView;
import dev.tomewisp.settings.capability.RecipeSettingsView;
import dev.tomewisp.settings.diagnostics.SettingsDiagnosticsAggregator;
import dev.tomewisp.settings.history.HistorySettingsView;
import dev.tomewisp.recipe.config.RecipeClientConfig;
import dev.tomewisp.tool.ToolResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Common owner for native settings actions and immutable UI snapshots. */
public final class ClientSettingsService implements AutoCloseable {
    public interface ModelActions {
        ToolResult<ModelState> save(
                ModelProfilesConfig candidate,
                Map<ModelMetadata.Key, ModelMetadata> metadata);

        default ToolResult<ModelState> save(
                ModelProfilesConfig candidate,
                String replacementProfileId,
                SecretValue replacement,
                Map<ModelMetadata.Key, ModelMetadata> metadata) {
            if (replacement != null) {
                return new ToolResult.Failure<>(
                        "credential_store_unavailable",
                        "Stored credentials are unavailable");
            }
            return save(candidate, metadata);
        }

        ToolResult<ModelState> reload(Map<ModelMetadata.Key, ModelMetadata> metadata);

        ToolResult<ResolvedModelProfile> resolve(
                ModelProfileDefinition candidate,
                Map<ModelMetadata.Key, ModelMetadata> metadata);

        default ToolResult<ResolvedModelProfile> resolve(
                ModelProfileDefinition candidate,
                SecretValue replacement,
                Map<ModelMetadata.Key, ModelMetadata> metadata) {
            if (replacement != null) {
                return new ToolResult.Failure<>(
                        "credential_store_unavailable",
                        "Stored credentials are unavailable");
            }
            return resolve(candidate, metadata);
        }

        ToolResult<PreparedModels> prepare(
                ModelProfilesConfig candidate,
                Map<ModelMetadata.Key, ModelMetadata> metadata);

        CompletableFuture<ModelConnectionResult> probe(
                ResolvedModelProfile profile, CancellationSignal cancellation);
    }

    public interface MetadataActions {
        CompletableFuture<Void> refresh();

        CompletableFuture<Void> closeAsync();
    }

    public interface CapabilityActions {
        ToolResult<CapabilitySettingsView> saveCapabilities(CapabilityPolicy candidate);

        ToolResult<CapabilitySettingsView> reloadCapabilities();
    }

    public interface RecipeActions {
        ToolResult<RecipeSettingsView> saveRecipes(RecipeClientConfig candidate);

        ToolResult<RecipeSettingsView> reloadRecipes();
    }

    public interface DisplayActions {
        ToolResult<GuideDisplayConfig> saveDisplay(GuideDisplayConfig candidate);

        ToolResult<GuideDisplayConfig> reloadDisplay();
    }

    public interface HistoryActions {
        HistoryRuntimeState state();

        CompletableFuture<ToolResult<Boolean>> deleteCurrentHistory();

        CompletableFuture<ToolResult<Boolean>> deleteActorHistory();

        CompletableFuture<ToolResult<Boolean>> resetHistoryDatabase();
    }

    public enum HistoryAction {
        DELETE_CURRENT,
        DELETE_ACTOR,
        RESET_DATABASE
    }

    public enum ConfirmationStage {
        FIRST,
        SECOND
    }

    public static final class HistoryConfirmationToken {
        private final HistoryAction action;
        private final ConfirmationStage stage;
        private final long generation;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private HistoryConfirmationToken(
                HistoryAction action, ConfirmationStage stage, long generation) {
            this.action = Objects.requireNonNull(action, "action");
            this.stage = Objects.requireNonNull(stage, "stage");
            if (generation < 0) {
                throw new IllegalArgumentException("confirmation generation must not be negative");
            }
            this.generation = generation;
        }

        public HistoryAction action() {
            return action;
        }

        public ConfirmationStage stage() {
            return stage;
        }

        public long generation() {
            return generation;
        }

        private boolean consume(
                HistoryAction expectedAction,
                ConfirmationStage expectedStage,
                long expectedGeneration) {
            return action == expectedAction
                    && stage == expectedStage
                    && generation == expectedGeneration
                    && consumed.compareAndSet(false, true);
        }

        @Override
        public String toString() {
            return "HistoryConfirmationToken[action=" + action
                    + ", stage=" + stage
                    + ", generation=" + generation + "]";
        }
    }

    public record HistoryRuntimeState(
            boolean configured,
            Optional<GuideSnapshot> guide,
            GuideHistoryActivity activity,
            SettingsDiagnosticsAggregator.HistoryScopeKind scopeKind) {
        public HistoryRuntimeState {
            guide = Objects.requireNonNull(guide, "guide");
            Objects.requireNonNull(activity, "activity");
            Objects.requireNonNull(scopeKind, "scopeKind");
            if (guide.isEmpty()
                    != (scopeKind == SettingsDiagnosticsAggregator.HistoryScopeKind.NONE)) {
                throw new IllegalArgumentException(
                        "history scope kind must match current Guide availability");
            }
        }

        public static HistoryRuntimeState disconnected() {
            return new HistoryRuntimeState(
                    false,
                    Optional.empty(),
                    GuideHistoryActivity.idle(),
                    SettingsDiagnosticsAggregator.HistoryScopeKind.NONE);
        }
    }

    public record ModelState(
            ModelProfilesConfig config,
            List<ModelProfileSettingsView.Resolution> profiles) {
        public ModelState {
            Objects.requireNonNull(config, "config");
            profiles = List.copyOf(profiles);
            if (profiles.size() != config.profiles().size()) {
                throw new IllegalArgumentException("every configured profile needs a resolution");
            }
            for (int index = 0; index < profiles.size(); index++) {
                if (!profiles.get(index).definition().equals(config.profiles().get(index))) {
                    throw new IllegalArgumentException("resolved profile order must match configuration");
                }
            }
        }
    }

    public record PreparedModels(ModelState state, Runnable publish) {
        public PreparedModels {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(publish, "publish");
        }
    }

    private final Object lock = new Object();
    private GuideDisplayConfig display;
    private final DisplayActions displayActions;
    private final Set<String> presentEnvironmentNames;
    private final ModelActions models;
    private final MetadataActions metadataActions;
    private final CapabilityActions capabilityActions;
    private final RecipeActions recipeActions;
    private final HistoryActions historyActions;
    private final ClientEventDispatcher dispatcher;
    private final Executor worker;
    private final CopyOnWriteArrayList<Consumer<ClientSettingsSnapshot>> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong operationIds = new AtomicLong();
    private final SettingsDiagnosticsAggregator diagnostics =
            new SettingsDiagnosticsAggregator();

    private ModelState modelState;
    private CapabilitySettingsView capabilityState;
    private RecipeSettingsView recipeState;
    private HistoryRuntimeState historyState;
    private long modelGeneration;
    private long metadataGeneration;
    private Map<ModelMetadata.Key, ModelMetadata> metadata = Map.of();
    private GuideFailure metadataFailure;
    private ModelConnectionResult connectionResult;
    private SettingsOperation operation = SettingsOperation.idle();
    private SettingsNotice notice;
    private ClientSettingsSnapshot snapshot;
    private ActiveProbe activeProbe;
    private boolean closed;

    public ClientSettingsService(
            GuideDisplayConfig display,
            ModelState initialModels,
            Set<String> presentEnvironmentNames,
            ModelActions models,
            MetadataActions metadataActions,
            ClientEventDispatcher dispatcher,
            Executor worker) {
        this(
                display,
                initialModels,
                presentEnvironmentNames,
                models,
                metadataActions,
                CapabilitySettingsView.defaults(),
                defaultCapabilityActions(),
                RecipeSettingsView.defaults(),
                defaultRecipeActions(),
                dispatcher,
                worker,
                null);
    }

    public ClientSettingsService(
            GuideDisplayConfig display,
            ModelState initialModels,
            Set<String> presentEnvironmentNames,
            ModelActions models,
            MetadataActions metadataActions,
            ClientEventDispatcher dispatcher,
            Executor worker,
            SettingsNotice initialNotice) {
        this(
                display,
                initialModels,
                presentEnvironmentNames,
                models,
                metadataActions,
                CapabilitySettingsView.defaults(),
                defaultCapabilityActions(),
                RecipeSettingsView.defaults(),
                defaultRecipeActions(),
                dispatcher,
                worker,
                initialNotice);
    }

    public ClientSettingsService(
            GuideDisplayConfig display,
            ModelState initialModels,
            Set<String> presentEnvironmentNames,
            ModelActions models,
            MetadataActions metadataActions,
            CapabilitySettingsView initialCapabilities,
            CapabilityActions capabilityActions,
            RecipeSettingsView initialRecipes,
            RecipeActions recipeActions,
            ClientEventDispatcher dispatcher,
            Executor worker,
            SettingsNotice initialNotice) {
        this(
                display,
                defaultDisplayActions(),
                initialModels,
                presentEnvironmentNames,
                models,
                metadataActions,
                initialCapabilities,
                capabilityActions,
                initialRecipes,
                recipeActions,
                defaultHistoryActions(),
                dispatcher,
                worker,
                initialNotice);
    }

    public ClientSettingsService(
            GuideDisplayConfig display,
            DisplayActions displayActions,
            ModelState initialModels,
            Set<String> presentEnvironmentNames,
            ModelActions models,
            MetadataActions metadataActions,
            CapabilitySettingsView initialCapabilities,
            CapabilityActions capabilityActions,
            RecipeSettingsView initialRecipes,
            RecipeActions recipeActions,
            HistoryActions historyActions,
            ClientEventDispatcher dispatcher,
            Executor worker,
            SettingsNotice initialNotice) {
        this.display = Objects.requireNonNull(display, "display");
        this.displayActions = Objects.requireNonNull(displayActions, "displayActions");
        this.modelState = Objects.requireNonNull(initialModels, "initialModels");
        this.presentEnvironmentNames = Collections.unmodifiableSet(
                new TreeSet<>(presentEnvironmentNames));
        this.models = Objects.requireNonNull(models, "models");
        this.metadataActions = Objects.requireNonNull(metadataActions, "metadataActions");
        this.capabilityState = Objects.requireNonNull(initialCapabilities, "initialCapabilities");
        this.capabilityActions = Objects.requireNonNull(capabilityActions, "capabilityActions");
        this.recipeState = Objects.requireNonNull(initialRecipes, "initialRecipes");
        this.recipeActions = Objects.requireNonNull(recipeActions, "recipeActions");
        this.historyActions = Objects.requireNonNull(historyActions, "historyActions");
        this.historyState = safeHistoryState(historyActions);
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.notice = initialNotice;
        this.snapshot = buildSnapshot(0);
    }

    public ClientSettingsSnapshot snapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    public AutoCloseable listen(Consumer<ClientSettingsSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        ClientSettingsSnapshot initial = snapshot();
        dispatcher.execute(() -> {
            if (listeners.contains(listener)) {
                listener.accept(initial);
            }
        });
        return () -> listeners.remove(listener);
    }

    public CompletableFuture<ToolResult<Boolean>> saveModels(ModelProfilesConfig candidate) {
        return saveModels(candidate, null, null);
    }

    public CompletableFuture<ToolResult<Boolean>> saveModels(
            ModelProfilesConfig candidate,
            String replacementProfileId,
            SecretValue replacement) {
        Objects.requireNonNull(candidate, "candidate");
        Reservation reservation = reserve(SettingsOperation.models(
                SettingsOperation.Kind.SAVING_MODELS));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(failed(reservation.failureCode()));
        }
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        Map<ModelMetadata.Key, ModelMetadata> metadataSnapshot = metadataSnapshot();
        worker.execute(() -> {
            ToolResult<ModelState> saved = safely(
                    () -> models.save(
                            candidate,
                            replacementProfileId,
                            replacement,
                            metadataSnapshot),
                    "settings_save_failed",
                    "Unable to save model settings");
            dispatcher.execute(() -> finishModels(reservation.id(), saved, result, "models_saved"));
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> reloadModels(boolean discardDirtyConfirmed) {
        if (!discardDirtyConfirmed) {
            return CompletableFuture.completedFuture(new ToolResult.Failure<>(
                    "settings_discard_confirmation_required",
                    "Reload requires confirmation to discard unsaved changes"));
        }
        Reservation reservation = reserve(SettingsOperation.models(
                SettingsOperation.Kind.RELOADING_MODELS));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(failed(reservation.failureCode()));
        }
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        Map<ModelMetadata.Key, ModelMetadata> metadataSnapshot = metadataSnapshot();
        worker.execute(() -> {
            ToolResult<ModelState> loaded = safely(
                    () -> models.reload(metadataSnapshot),
                    "settings_reload_failed",
                    "Unable to reload model settings");
            dispatcher.execute(() -> finishModels(
                    reservation.id(), loaded, result, "models_reloaded"));
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> saveCapabilities(CapabilityPolicy candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Reservation reservation = reserve(SettingsOperation.domain(
                SettingsOperation.Kind.SAVING_CAPABILITIES));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(failed(reservation.failureCode()));
        }
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        worker.execute(() -> {
            ToolResult<CapabilitySettingsView> saved = safely(
                    () -> capabilityActions.saveCapabilities(candidate),
                    "settings_save_failed",
                    "Unable to save capability settings");
            dispatcher.execute(() -> finishCapabilities(
                    reservation.id(), saved, result, "capabilities_saved"));
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> reloadCapabilities(
            boolean discardDirtyConfirmed) {
        if (!discardDirtyConfirmed) {
            return discardConfirmation();
        }
        Reservation reservation = reserve(SettingsOperation.domain(
                SettingsOperation.Kind.RELOADING_CAPABILITIES));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(failed(reservation.failureCode()));
        }
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        worker.execute(() -> {
            ToolResult<CapabilitySettingsView> loaded = safely(
                    capabilityActions::reloadCapabilities,
                    "settings_reload_failed",
                    "Unable to reload capability settings");
            dispatcher.execute(() -> finishCapabilities(
                    reservation.id(), loaded, result, "capabilities_reloaded"));
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> saveRecipeSettings(
            RecipeClientConfig candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Reservation reservation = reserve(SettingsOperation.domain(
                SettingsOperation.Kind.SAVING_RECIPES));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(failed(reservation.failureCode()));
        }
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        worker.execute(() -> {
            ToolResult<RecipeSettingsView> saved = safely(
                    () -> recipeActions.saveRecipes(candidate),
                    "settings_save_failed",
                    "Unable to save recipe settings");
            dispatcher.execute(() -> finishRecipes(
                    reservation.id(), saved, result, "recipes_saved"));
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> reloadRecipeSettings(
            boolean discardDirtyConfirmed) {
        if (!discardDirtyConfirmed) {
            return discardConfirmation();
        }
        Reservation reservation = reserve(SettingsOperation.domain(
                SettingsOperation.Kind.RELOADING_RECIPES));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(failed(reservation.failureCode()));
        }
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        worker.execute(() -> {
            ToolResult<RecipeSettingsView> loaded = safely(
                    recipeActions::reloadRecipes,
                    "settings_reload_failed",
                    "Unable to reload recipe settings");
            dispatcher.execute(() -> finishRecipes(
                    reservation.id(), loaded, result, "recipes_reloaded"));
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> saveDisplay(GuideDisplayConfig candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Reservation reservation = reserve(SettingsOperation.domain(
                SettingsOperation.Kind.SAVING_DISPLAY));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(failed(reservation.failureCode()));
        }
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        worker.execute(() -> {
            ToolResult<GuideDisplayConfig> saved = safely(
                    () -> displayActions.saveDisplay(candidate),
                    "settings_save_failed",
                    "Unable to save display settings");
            dispatcher.execute(() -> finishDisplay(
                    reservation.id(), saved, result, "display_saved"));
        });
        return result;
    }

    public CompletableFuture<ToolResult<Boolean>> reloadDisplay() {
        Reservation reservation = reserve(SettingsOperation.domain(
                SettingsOperation.Kind.RELOADING_DISPLAY));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(failed(reservation.failureCode()));
        }
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        worker.execute(() -> {
            ToolResult<GuideDisplayConfig> loaded = safely(
                    displayActions::reloadDisplay,
                    "settings_reload_failed",
                    "Unable to reload display settings");
            dispatcher.execute(() -> finishDisplay(
                    reservation.id(), loaded, result, "display_reloaded"));
        });
        return result;
    }

    public void refreshRuntimeState() {
        HistoryRuntimeState refreshed = safeHistoryState(historyActions);
        synchronized (lock) {
            if (closed || refreshed.equals(historyState)) {
                return;
            }
            historyState = refreshed;
            publishLocked();
        }
    }

    public ToolResult<HistoryConfirmationToken> requestHistoryConfirmation(
            HistoryAction action) {
        Objects.requireNonNull(action, "action");
        synchronized (lock) {
            GuideFailure failure = confirmationFailureLocked(action);
            if (failure != null) {
                return new ToolResult.Failure<>(failure.code(), failure.message());
            }
            return new ToolResult.Success<>(new HistoryConfirmationToken(
                    action, ConfirmationStage.FIRST, snapshot.generation()));
        }
    }

    public ToolResult<HistoryConfirmationToken> confirmHistoryReset(
            HistoryConfirmationToken first) {
        synchronized (lock) {
            GuideFailure failure = confirmationFailureLocked(HistoryAction.RESET_DATABASE);
            if (failure != null) {
                return new ToolResult.Failure<>(failure.code(), failure.message());
            }
            if (first == null || !first.consume(
                    HistoryAction.RESET_DATABASE,
                    ConfirmationStage.FIRST,
                    snapshot.generation())) {
                return confirmationRequired();
            }
            return new ToolResult.Success<>(new HistoryConfirmationToken(
                    HistoryAction.RESET_DATABASE,
                    ConfirmationStage.SECOND,
                    snapshot.generation()));
        }
    }

    public CompletableFuture<ToolResult<Boolean>> deleteCurrentHistory(
            HistoryConfirmationToken confirmation) {
        return administerHistory(HistoryAction.DELETE_CURRENT, confirmation);
    }

    public CompletableFuture<ToolResult<Boolean>> deleteActorHistory(
            HistoryConfirmationToken confirmation) {
        return administerHistory(HistoryAction.DELETE_ACTOR, confirmation);
    }

    public CompletableFuture<ToolResult<Boolean>> resetHistoryDatabase(
            HistoryConfirmationToken confirmation) {
        return administerHistory(HistoryAction.RESET_DATABASE, confirmation);
    }

    public CompletableFuture<ToolResult<Boolean>> refreshMetadata() {
        Reservation reservation = reserve(SettingsOperation.models(
                SettingsOperation.Kind.REFRESHING_METADATA));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(failed(reservation.failureCode()));
        }
        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        CompletableFuture<Void> refresh;
        try {
            refresh = metadataActions.refresh();
        } catch (RuntimeException failure) {
            refresh = CompletableFuture.failedFuture(failure);
        }
        refresh.whenComplete((ignored, failure) -> dispatcher.execute(() -> {
            synchronized (lock) {
                if (!isCurrentLocked(reservation.id())) {
                    return;
                }
                operation = SettingsOperation.idle();
                if (failure == null) {
                    notice = SettingsNotice.success(
                            "metadata_refreshed", "Model metadata refresh completed");
                    result.complete(new ToolResult.Success<>(Boolean.TRUE));
                } else {
                    notice = SettingsNotice.failure(
                            "metadata_unavailable", "Model metadata refresh is unavailable");
                    result.complete(new ToolResult.Failure<>(notice.code(), notice.message()));
                }
                publishLocked();
            }
        }));
        return result;
    }

    public CompletableFuture<ModelConnectionResult> testConnection(
            ModelProfileDefinition candidate) {
        return testConnection(candidate, null);
    }

    public CompletableFuture<ModelConnectionResult> testConnection(
            ModelProfileDefinition candidate,
            SecretValue replacement) {
        Objects.requireNonNull(candidate, "candidate");
        Reservation reservation = reserve(SettingsOperation.probe(candidate.id()));
        if (!reservation.accepted()) {
            return CompletableFuture.completedFuture(new ModelConnectionResult.Failure(
                    "connection_test_busy", "Another settings operation is already running"));
        }
        CancellationSignal cancellation = new CancellationSignal();
        CompletableFuture<ModelConnectionResult> outward = new CompletableFuture<>();
        synchronized (lock) {
            activeProbe = new ActiveProbe(reservation.id(), cancellation, outward);
        }
        Map<ModelMetadata.Key, ModelMetadata> metadataSnapshot = metadataSnapshot();
        worker.execute(() -> startProbe(
                reservation.id(),
                candidate,
                replacement,
                metadataSnapshot,
                cancellation,
                outward));
        return outward;
    }

    public boolean cancelConnectionTest() {
        ActiveProbe probe;
        ModelConnectionResult.Failure cancelled;
        synchronized (lock) {
            probe = activeProbe;
            if (probe == null || !isCurrentLocked(probe.id())) {
                return false;
            }
            activeProbe = null;
            operation = SettingsOperation.idle();
            cancelled = connectionFailure(
                    "connection_cancelled", "The connection test was cancelled");
            connectionResult = cancelled;
            notice = SettingsNotice.failure(cancelled.code(), cancelled.message());
            publishLocked();
        }
        probe.cancellation().cancel();
        probe.result().complete(cancelled);
        return true;
    }

    public void acceptMetadataUpdate(ModelMetadataUpdate update) {
        Objects.requireNonNull(update, "update");
        long expectedGeneration;
        long expectedMetadataGeneration;
        ModelProfilesConfig config;
        synchronized (lock) {
            if (closed) {
                return;
            }
            metadata = Map.copyOf(update.entries());
            metadataFailure = update.failure();
            expectedMetadataGeneration = ++metadataGeneration;
            expectedGeneration = modelGeneration;
            config = modelState.config();
            publishLocked();
        }
        reconcileMetadata(
                expectedGeneration,
                expectedMetadataGeneration,
                config,
                update.entries());
    }

    public CompletableFuture<Void> closeAsync() {
        synchronized (lock) {
            closed = true;
        }
        cancelConnectionTest();
        try {
            return metadataActions.closeAsync().exceptionally(ignored -> null);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void close() {
        closeAsync();
    }

    private void startProbe(
            long operationId,
            ModelProfileDefinition candidate,
            SecretValue replacement,
            Map<ModelMetadata.Key, ModelMetadata> metadataSnapshot,
            CancellationSignal cancellation,
            CompletableFuture<ModelConnectionResult> outward) {
        if (!isCurrent(operationId)) {
            return;
        }
        ToolResult<ResolvedModelProfile> resolved = safely(
                () -> models.resolve(candidate, replacement, metadataSnapshot),
                "invalid_model_config",
                "Unable to prepare the connection test");
        if (resolved instanceof ToolResult.Failure<ResolvedModelProfile> failure) {
            dispatcher.execute(() -> finishProbe(
                    operationId,
                    connectionFailure(failure.code(), safeProbeMessage(failure.code())),
                    outward));
            return;
        }
        ResolvedModelProfile profile = ((ToolResult.Success<ResolvedModelProfile>) resolved).value();
        CompletableFuture<ModelConnectionResult> request;
        try {
            request = models.probe(profile, cancellation);
        } catch (RuntimeException failure) {
            request = CompletableFuture.completedFuture(connectionFailure(
                    "connection_transport_failed", "The model provider could not be reached"));
        }
        request.whenComplete((probeResult, thrown) -> dispatcher.execute(() -> finishProbe(
                operationId,
                thrown == null && probeResult != null
                        ? probeResult
                        : connectionFailure(
                                cancellation.isCancelled()
                                        ? "connection_cancelled"
                                        : "connection_transport_failed",
                                cancellation.isCancelled()
                                        ? "The connection test was cancelled"
                                        : "The model provider could not be reached"),
                outward)));
    }

    private void finishProbe(
            long operationId,
            ModelConnectionResult result,
            CompletableFuture<ModelConnectionResult> outward) {
        synchronized (lock) {
            if (!isCurrentLocked(operationId)) {
                return;
            }
            activeProbe = null;
            operation = SettingsOperation.idle();
            connectionResult = result;
            if (result instanceof ModelConnectionResult.Success) {
                notice = SettingsNotice.success(
                        "connection_succeeded", "The model connection test succeeded");
            } else {
                ModelConnectionResult.Failure failure = (ModelConnectionResult.Failure) result;
                notice = SettingsNotice.failure(failure.code(), failure.message());
            }
            publishLocked();
        }
        outward.complete(result);
    }

    private void finishModels(
            long operationId,
            ToolResult<ModelState> completed,
            CompletableFuture<ToolResult<Boolean>> outward,
            String successCode) {
        ToolResult<Boolean> result;
        synchronized (lock) {
            if (!isCurrentLocked(operationId)) {
                return;
            }
            operation = SettingsOperation.idle();
            if (completed instanceof ToolResult.Success<ModelState> success) {
                modelState = success.value();
                modelGeneration++;
                connectionResult = null;
                notice = SettingsNotice.success(successCode, switch (successCode) {
                    case "models_reloaded" -> "Model settings reloaded";
                    default -> "Model settings saved";
                });
                result = new ToolResult.Success<>(Boolean.TRUE);
            } else {
                ToolResult.Failure<ModelState> failure =
                        (ToolResult.Failure<ModelState>) completed;
                notice = SettingsNotice.failure(failure.code(), failure.message());
                result = new ToolResult.Failure<>(failure.code(), failure.message());
            }
            publishLocked();
        }
        outward.complete(result);
    }

    private void finishCapabilities(
            long operationId,
            ToolResult<CapabilitySettingsView> completed,
            CompletableFuture<ToolResult<Boolean>> outward,
            String successCode) {
        ToolResult<Boolean> result;
        synchronized (lock) {
            if (!isCurrentLocked(operationId)) {
                return;
            }
            operation = SettingsOperation.idle();
            if (completed instanceof ToolResult.Success<CapabilitySettingsView> success) {
                capabilityState = success.value();
                notice = SettingsNotice.success(
                        successCode,
                        successCode.equals("capabilities_reloaded")
                                ? "Capability settings reloaded"
                                : "Capability settings saved");
                result = new ToolResult.Success<>(Boolean.TRUE);
            } else {
                ToolResult.Failure<CapabilitySettingsView> failure =
                        (ToolResult.Failure<CapabilitySettingsView>) completed;
                notice = SettingsNotice.failure(failure.code(), failure.message());
                result = new ToolResult.Failure<>(failure.code(), failure.message());
            }
            publishLocked();
        }
        outward.complete(result);
    }

    private void finishRecipes(
            long operationId,
            ToolResult<RecipeSettingsView> completed,
            CompletableFuture<ToolResult<Boolean>> outward,
            String successCode) {
        ToolResult<Boolean> result;
        synchronized (lock) {
            if (!isCurrentLocked(operationId)) {
                return;
            }
            operation = SettingsOperation.idle();
            if (completed instanceof ToolResult.Success<RecipeSettingsView> success) {
                recipeState = success.value();
                notice = SettingsNotice.success(
                        successCode,
                        successCode.equals("recipes_reloaded")
                                ? "Recipe settings reloaded"
                                : "Recipe settings saved");
                result = new ToolResult.Success<>(Boolean.TRUE);
            } else {
                ToolResult.Failure<RecipeSettingsView> failure =
                        (ToolResult.Failure<RecipeSettingsView>) completed;
                notice = SettingsNotice.failure(failure.code(), failure.message());
                result = new ToolResult.Failure<>(failure.code(), failure.message());
            }
            publishLocked();
        }
        outward.complete(result);
    }

    private void finishDisplay(
            long operationId,
            ToolResult<GuideDisplayConfig> completed,
            CompletableFuture<ToolResult<Boolean>> outward,
            String successCode) {
        ToolResult<Boolean> result;
        synchronized (lock) {
            if (!isCurrentLocked(operationId)) {
                return;
            }
            operation = SettingsOperation.idle();
            if (completed instanceof ToolResult.Success<GuideDisplayConfig> success) {
                display = success.value();
                notice = SettingsNotice.success(
                        successCode,
                        successCode.equals("display_reloaded")
                                ? "Display settings reloaded"
                                : "Display settings saved");
                result = new ToolResult.Success<>(Boolean.TRUE);
            } else {
                ToolResult.Failure<GuideDisplayConfig> failure =
                        (ToolResult.Failure<GuideDisplayConfig>) completed;
                notice = SettingsNotice.failure(failure.code(), failure.message());
                result = new ToolResult.Failure<>(failure.code(), failure.message());
            }
            publishLocked();
        }
        outward.complete(result);
    }

    private CompletableFuture<ToolResult<Boolean>> administerHistory(
            HistoryAction action,
            HistoryConfirmationToken confirmation) {
        Objects.requireNonNull(action, "action");
        Reservation reservation;
        synchronized (lock) {
            GuideFailure failure = confirmationFailureLocked(action);
            if (failure != null) {
                return CompletableFuture.completedFuture(
                        new ToolResult.Failure<>(failure.code(), failure.message()));
            }
            ConfirmationStage stage = action == HistoryAction.RESET_DATABASE
                    ? ConfirmationStage.SECOND
                    : ConfirmationStage.FIRST;
            if (confirmation == null || !confirmation.consume(
                    action, stage, snapshot.generation())) {
                return CompletableFuture.completedFuture(confirmationRequired());
            }
            reservation = reserveLocked(historyOperation(action));
        }

        CompletableFuture<ToolResult<Boolean>> result = new CompletableFuture<>();
        CompletableFuture<ToolResult<Boolean>> operationFuture;
        try {
            operationFuture = switch (action) {
                case DELETE_CURRENT -> historyActions.deleteCurrentHistory();
                case DELETE_ACTOR -> historyActions.deleteActorHistory();
                case RESET_DATABASE -> historyActions.resetHistoryDatabase();
            };
            Objects.requireNonNull(operationFuture, "history action future");
        } catch (RuntimeException failure) {
            operationFuture = CompletableFuture.completedFuture(new ToolResult.Failure<>(
                    "history_delete_failed", "Unable to update Guide history"));
        }
        operationFuture.whenComplete((completed, thrown) -> dispatcher.execute(() ->
                finishHistory(
                        reservation.id(),
                        completed,
                        thrown,
                        action,
                        result)));
        return result;
    }

    private void finishHistory(
            long operationId,
            ToolResult<Boolean> completed,
            Throwable thrown,
            HistoryAction action,
            CompletableFuture<ToolResult<Boolean>> outward) {
        HistoryRuntimeState refreshed = safeHistoryState(historyActions);
        ToolResult<Boolean> result;
        synchronized (lock) {
            if (!isCurrentLocked(operationId)) {
                return;
            }
            operation = SettingsOperation.idle();
            historyState = refreshed;
            if (thrown == null && completed instanceof ToolResult.Success<Boolean>) {
                String code = switch (action) {
                    case DELETE_CURRENT -> "history_current_deleted";
                    case DELETE_ACTOR -> "history_actor_deleted";
                    case RESET_DATABASE -> "history_database_reset";
                };
                notice = SettingsNotice.success(code, "Guide history updated");
                result = new ToolResult.Success<>(Boolean.TRUE);
            } else {
                ToolResult.Failure<Boolean> failure =
                        thrown == null && completed instanceof ToolResult.Failure<Boolean> value
                                ? value
                                : new ToolResult.Failure<>(
                                        "history_delete_failed",
                                        "Unable to update Guide history");
                notice = SettingsNotice.failure(failure.code(), failure.message());
                result = new ToolResult.Failure<>(failure.code(), failure.message());
            }
            publishLocked();
        }
        outward.complete(result);
    }

    private void reconcileMetadata(
            long expectedGeneration,
            long expectedMetadataGeneration,
            ModelProfilesConfig config,
            Map<ModelMetadata.Key, ModelMetadata> entries) {
        worker.execute(() -> {
            ToolResult<PreparedModels> prepared = safely(
                    () -> models.prepare(config, Map.copyOf(entries)),
                    "metadata_unavailable",
                    "Model metadata reconciliation is unavailable");
            dispatcher.execute(() -> finishMetadataReconciliation(
                    expectedGeneration, expectedMetadataGeneration, config, prepared));
        });
    }

    private void finishMetadataReconciliation(
            long expectedGeneration,
            long expectedMetadataGeneration,
            ModelProfilesConfig preparedConfig,
            ToolResult<PreparedModels> prepared) {
        ModelProfilesConfig retryConfig = null;
        long retryGeneration = -1;
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (modelGeneration != expectedGeneration
                    || metadataGeneration != expectedMetadataGeneration
                    || modelState.config() != preparedConfig) {
                retryConfig = modelState.config();
                retryGeneration = modelGeneration;
            } else if (prepared instanceof ToolResult.Success<PreparedModels> success) {
                try {
                    success.value().publish().run();
                    modelState = success.value().state();
                } catch (RuntimeException failure) {
                    metadataFailure = new GuideFailure(
                            "metadata_unavailable", "Model metadata reconciliation is unavailable");
                }
                publishLocked();
            } else {
                ToolResult.Failure<PreparedModels> failure =
                        (ToolResult.Failure<PreparedModels>) prepared;
                metadataFailure = new GuideFailure(failure.code(), failure.message());
                publishLocked();
            }
        }
        if (retryConfig != null) {
            long currentMetadataGeneration;
            synchronized (lock) {
                currentMetadataGeneration = metadataGeneration;
            }
            reconcileMetadata(
                    retryGeneration,
                    currentMetadataGeneration,
                    retryConfig,
                    metadataSnapshot());
        }
    }

    private Reservation reserve(SettingsOperation requested) {
        synchronized (lock) {
            if (closed) {
                return Reservation.rejected("settings_closed");
            }
            if (operation.kind() != SettingsOperation.Kind.IDLE) {
                return Reservation.rejected("settings_busy");
            }
            return reserveLocked(requested);
        }
    }

    private Reservation reserveLocked(SettingsOperation requested) {
        long id = operationIds.incrementAndGet();
        operation = Objects.requireNonNull(requested, "requested");
        notice = null;
        publishLocked();
        return Reservation.accepted(id);
    }

    private boolean isCurrent(long id) {
        synchronized (lock) {
            return isCurrentLocked(id);
        }
    }

    private boolean isCurrentLocked(long id) {
        return operationIds.get() == id && operation.kind() != SettingsOperation.Kind.IDLE;
    }

    private Map<ModelMetadata.Key, ModelMetadata> metadataSnapshot() {
        synchronized (lock) {
            return metadata;
        }
    }

    private void publishLocked() {
        snapshot = buildSnapshot(snapshot.generation() + 1);
        ClientSettingsSnapshot published = snapshot;
        dispatcher.execute(() -> listeners.forEach(listener -> listener.accept(published)));
    }

    private ClientSettingsSnapshot buildSnapshot(long generation) {
        ModelProfileSettingsView modelView = ModelProfileSettingsView.from(
                modelState.config(),
                modelState.profiles(),
                presentEnvironmentNames,
                metadataFailure,
                connectionResult);
        HistorySettingsView historyView = historyView(historyState);
        return new ClientSettingsSnapshot(
                generation,
                display,
                modelView,
                capabilityState,
                recipeState,
                historyView,
                diagnostics.snapshot(
                        display.debugMode(),
                        new SettingsDiagnosticsAggregator.DiagnosticsInputs(
                                generation,
                                modelView,
                                capabilityState,
                                recipeState,
                                historyState.guide(),
                                historyState.activity(),
                                historyState.scopeKind(),
                                GuideHistoryPartition.SCHEMA_VERSION,
                                List.of())),
                operation,
                notice);
    }

    private GuideFailure confirmationFailureLocked(HistoryAction action) {
        if (closed) {
            return new GuideFailure("settings_closed", "Settings are closed");
        }
        if (operation.kind() != SettingsOperation.Kind.IDLE) {
            return new GuideFailure(
                    "settings_busy", "Another settings operation is already running");
        }
        if (action == HistoryAction.RESET_DATABASE && !display.debugMode()) {
            return new GuideFailure(
                    "history_delete_confirmation_required",
                    "Debug Mode and a fresh second confirmation are required");
        }
        return historyFailure(historyState, action);
    }

    private static GuideFailure historyFailure(
            HistoryRuntimeState state, HistoryAction action) {
        if (!state.configured() || state.guide().isEmpty()) {
            return new GuideFailure(
                    "history_unavailable", "Durable Guide history is unavailable");
        }
        GuideSnapshot guide = state.guide().orElseThrow();
        long active = activeRequestCount(guide);
        if (!state.activity().idleForDeletion()
                || active > 0
                || guide.persistence().state() == GuidePersistenceSnapshot.State.SAVING) {
            return new GuideFailure("history_delete_busy", "Guide history is busy");
        }
        if (guide.persistence().state() == GuidePersistenceSnapshot.State.LOADING) {
            return new GuideFailure(
                    "history_loading", "Durable Guide history is still loading");
        }
        if (action != HistoryAction.RESET_DATABASE
                && guide.persistence().state() != GuidePersistenceSnapshot.State.AVAILABLE) {
            return new GuideFailure(
                    "history_unavailable", "Durable Guide history is unavailable");
        }
        return null;
    }

    private static HistorySettingsView historyView(HistoryRuntimeState state) {
        if (state.guide().isEmpty()) {
            return HistorySettingsView.disconnected();
        }
        GuideSnapshot guide = state.guide().orElseThrow();
        long active = activeRequestCount(guide);
        boolean busy = !state.activity().idleForDeletion()
                || active > 0
                || guide.persistence().state() == GuidePersistenceSnapshot.State.SAVING;
        HistorySettingsView.Health health = !state.configured()
                ? HistorySettingsView.Health.UNAVAILABLE
                : switch (guide.persistence().state()) {
                    case AVAILABLE -> busy
                            ? HistorySettingsView.Health.WORKING
                            : HistorySettingsView.Health.READY;
                    case LOADING, SAVING -> HistorySettingsView.Health.WORKING;
                    case DISABLED -> HistorySettingsView.Health.ATTENTION;
                    case UNAVAILABLE -> HistorySettingsView.Health.UNAVAILABLE;
                };
        boolean normalAvailable = state.configured()
                && !busy
                && guide.persistence().state() == GuidePersistenceSnapshot.State.AVAILABLE;
        boolean resetAvailable = state.configured()
                && !busy
                && guide.persistence().state() != GuidePersistenceSnapshot.State.LOADING;
        return new HistorySettingsView(
                switch (state.scopeKind()) {
                    case NONE -> HistorySettingsView.ConnectionKind.NONE;
                    case SINGLEPLAYER_WORLD ->
                            HistorySettingsView.ConnectionKind.SINGLEPLAYER_WORLD;
                    case MULTIPLAYER_SERVER ->
                            HistorySettingsView.ConnectionKind.MULTIPLAYER_SERVER;
                },
                health,
                state.activity().pendingWrites(),
                state.activity().deleting(),
                active,
                normalAvailable,
                normalAvailable,
                resetAvailable);
    }

    private static long activeRequestCount(GuideSnapshot guide) {
        return guide.sessions().stream()
                .flatMap(session -> session.requests().stream())
                .filter(request -> !request.terminal())
                .count();
    }

    private static SettingsOperation historyOperation(HistoryAction action) {
        return SettingsOperation.domain(switch (action) {
            case DELETE_CURRENT -> SettingsOperation.Kind.DELETING_CURRENT_HISTORY;
            case DELETE_ACTOR -> SettingsOperation.Kind.DELETING_ACTOR_HISTORY;
            case RESET_DATABASE -> SettingsOperation.Kind.RESETTING_HISTORY_DATABASE;
        });
    }

    private static <T> ToolResult<T> confirmationRequired() {
        return new ToolResult.Failure<>(
                "history_delete_confirmation_required",
                "A fresh history confirmation is required");
    }

    private static CompletableFuture<ToolResult<Boolean>> discardConfirmation() {
        return CompletableFuture.completedFuture(new ToolResult.Failure<>(
                "settings_discard_confirmation_required",
                "Reload requires confirmation to discard unsaved changes"));
    }

    private static ToolResult<Boolean> failed(String code) {
        return new ToolResult.Failure<>(code, switch (code) {
            case "settings_closed" -> "Settings are closed";
            default -> "Another settings operation is already running";
        });
    }

    private static ModelConnectionResult.Failure connectionFailure(
            String code, String message) {
        return new ModelConnectionResult.Failure(code, message);
    }

    private static String safeProbeMessage(String code) {
        return switch (code) {
            case "model_not_configured" -> "The configured credential is unavailable";
            case "model_disabled" -> "The model profile is disabled";
            default -> "The model profile is invalid";
        };
    }

    private static <T> ToolResult<T> safely(
            Supplier<ToolResult<T>> action,
            String code,
            String message) {
        try {
            return Objects.requireNonNull(action.get(), "settings action result");
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>(code, message);
        }
    }

    private static CapabilityActions defaultCapabilityActions() {
        return new CapabilityActions() {
            @Override
            public ToolResult<CapabilitySettingsView> saveCapabilities(
                    CapabilityPolicy candidate) {
                return new ToolResult.Failure<>(
                        "settings_unavailable", "Capability settings are unavailable");
            }

            @Override
            public ToolResult<CapabilitySettingsView> reloadCapabilities() {
                return new ToolResult.Failure<>(
                        "settings_unavailable", "Capability settings are unavailable");
            }
        };
    }

    private static RecipeActions defaultRecipeActions() {
        return new RecipeActions() {
            @Override
            public ToolResult<RecipeSettingsView> saveRecipes(RecipeClientConfig candidate) {
                return new ToolResult.Failure<>(
                        "settings_unavailable", "Recipe settings are unavailable");
            }

            @Override
            public ToolResult<RecipeSettingsView> reloadRecipes() {
                return new ToolResult.Failure<>(
                        "settings_unavailable", "Recipe settings are unavailable");
            }
        };
    }

    private static DisplayActions defaultDisplayActions() {
        return new DisplayActions() {
            @Override
            public ToolResult<GuideDisplayConfig> saveDisplay(GuideDisplayConfig candidate) {
                return new ToolResult.Failure<>(
                        "settings_unavailable", "Display settings are unavailable");
            }

            @Override
            public ToolResult<GuideDisplayConfig> reloadDisplay() {
                return new ToolResult.Failure<>(
                        "settings_unavailable", "Display settings are unavailable");
            }
        };
    }

    private static HistoryActions defaultHistoryActions() {
        return new HistoryActions() {
            @Override
            public HistoryRuntimeState state() {
                return HistoryRuntimeState.disconnected();
            }

            @Override
            public CompletableFuture<ToolResult<Boolean>> deleteCurrentHistory() {
                return unavailableHistory();
            }

            @Override
            public CompletableFuture<ToolResult<Boolean>> deleteActorHistory() {
                return unavailableHistory();
            }

            @Override
            public CompletableFuture<ToolResult<Boolean>> resetHistoryDatabase() {
                return unavailableHistory();
            }
        };
    }

    private static CompletableFuture<ToolResult<Boolean>> unavailableHistory() {
        return CompletableFuture.completedFuture(new ToolResult.Failure<>(
                "history_unavailable", "Durable Guide history is unavailable"));
    }

    private static HistoryRuntimeState safeHistoryState(HistoryActions actions) {
        try {
            return Objects.requireNonNull(actions.state(), "history state");
        } catch (RuntimeException failure) {
            return HistoryRuntimeState.disconnected();
        }
    }

    private record Reservation(long id, String failureCode) {
        private static Reservation accepted(long id) {
            return new Reservation(id, null);
        }

        private static Reservation rejected(String code) {
            return new Reservation(-1, code);
        }

        private boolean accepted() {
            return failureCode == null;
        }
    }

    private record ActiveProbe(
            long id,
            CancellationSignal cancellation,
            CompletableFuture<ModelConnectionResult> result) {}
}
