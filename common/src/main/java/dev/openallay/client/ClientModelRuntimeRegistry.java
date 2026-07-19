package dev.openallay.client;

import com.google.gson.Gson;
import dev.openallay.OpenAllayRuntime;
import dev.openallay.agent.AgentEvent;
import dev.openallay.agent.AgentResult;
import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.agent.session.AgentSessionKey;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.capability.CapabilityPolicy;
import dev.openallay.capability.ClientCapabilityResolver;
import dev.openallay.capability.ClientCapabilitySnapshot;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.guide.GuideClientModelProfile;
import dev.openallay.guide.GuideFailure;
import dev.openallay.guide.GuideContextSpec;
import dev.openallay.guide.GuideLocalEndpoint;
import dev.openallay.guide.GuideMessage;
import dev.openallay.guide.GuideModelProfileException;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRole;
import dev.openallay.model.config.ModelProfilesConfigLoader;
import dev.openallay.model.config.ResolvedModelProfile;
import dev.openallay.model.ProviderModelClients;
import dev.openallay.agent.trace.LiveTraceStore;
import dev.openallay.tool.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Atomic named-profile registry whose runtimes share provider-neutral sessions. */
public final class ClientModelRuntimeRegistry implements GuideLocalEndpoint {
    private final Gson gson;
    private final ClientEventDispatcher dispatcher;
    private final AgentToolExecutor extension;
    private final Function<ResolvedModelProfile, ModelClient> modelFactory;
    private final AgentSessionStore sessions = new AgentSessionStore();
    private final AtomicReference<State> state = new AtomicReference<>();
    private final AtomicReference<ClientCapabilitySnapshot> capabilities;

    ClientModelRuntimeRegistry(
            OpenAllayRuntime productRuntime,
            ModelProfilesConfigLoader.Load initial,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            Function<ResolvedModelProfile, ModelClient> modelFactory) {
        Objects.requireNonNull(productRuntime, "productRuntime");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.extension = extension;
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
        capabilities = new AtomicReference<>(resolveDefaultCapabilities(productRuntime));
        replace(initial, modelFactory);
    }

    public static ToolResult<ClientModelRuntimeRegistry> create(
            OpenAllayRuntime runtime,
            Path profilesPath,
            Path legacyPath,
            Map<String, String> environment,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension) {
        ToolResult<ModelProfilesConfigLoader.Load> loaded = new ModelProfilesConfigLoader()
                .load(profilesPath, legacyPath, environment);
        if (loaded instanceof ToolResult.Failure<ModelProfilesConfigLoader.Load> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        Gson gson = new Gson();
        ModelProfilesConfigLoader.Load value =
                ((ToolResult.Success<ModelProfilesConfigLoader.Load>) loaded).value();
        return new ToolResult.Success<>(create(
                runtime, value, gson, dispatcher, extension));
    }

    public static ClientModelRuntimeRegistry create(
            OpenAllayRuntime runtime,
            ModelProfilesConfigLoader.Load initial,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension) {
        Function<ResolvedModelProfile, ModelClient> factory = profile ->
                ProviderModelClients.create(profile.runtimeConfig(), gson);
        return new ClientModelRuntimeRegistry(
                runtime, initial, gson, dispatcher, extension, factory);
    }

    public synchronized void replace(
            ModelProfilesConfigLoader.Load replacement,
            Function<ResolvedModelProfile, ModelClient> replacementFactory) {
        Objects.requireNonNull(replacementFactory, "replacementFactory");
        state.set(build(replacement, replacementFactory));
    }

    public synchronized void replace(ModelProfilesConfigLoader.Load replacement) {
        prepare(replacement).publish();
    }

    public synchronized PreparedReplacement prepare(
            ModelProfilesConfigLoader.Load replacement) {
        return new PreparedReplacement(this, build(replacement, modelFactory));
    }

    /** Publishes a prepared capability view for future requests without replacing endpoints. */
    public synchronized void replaceCapabilities(ClientCapabilitySnapshot replacement) {
        Objects.requireNonNull(replacement, "replacement");
        State current = state.get();
        Map<String, ClientGuideRuntime> runtimes = new LinkedHashMap<>();
        current.runtimes().forEach((id, runtime) ->
                runtimes.put(id, runtime.withCapabilities(replacement)));
        capabilities.set(replacement);
        state.set(new State(current.defaultProfileId(), current.profiles(), runtimes));
    }

    public ClientCapabilitySnapshot capabilities() {
        return capabilities.get();
    }

    Object endpointIdentity(String profileId) {
        return runtime(state.get(), profileId).endpointIdentity();
    }

    @Override
    public String defaultProfileId() {
        return state.get().defaultProfileId();
    }

    @Override
    public List<GuideClientModelProfile> profiles() {
        return state.get().profiles();
    }

    @Override
    public Set<ContextCapability> requiredContext() {
        State captured = state.get();
        ClientGuideRuntime runtime = captured.runtimes().get(captured.defaultProfileId());
        return runtime == null ? Set.of() : runtime.requiredContext();
    }

    @Override
    public Set<ContextCapability> requiredContext(String profileId) {
        return runtime(state.get(), profileId).requiredContext();
    }

    @Override
    public java.util.Optional<GuideContextSpec> contextSpec(String profileId) {
        return runtime(state.get(), profileId).contextSpec(profileId);
    }

    @Override
    public CompletableFuture<AgentResult> ask(
            UUID actor,
            String sessionId,
            UUID requestId,
            String question,
            ToolInvocationContext context,
            Consumer<AgentEvent> events) {
        return ask(defaultProfileId(), actor, sessionId, requestId, question, context, events);
    }

    @Override
    public CompletableFuture<AgentResult> ask(
            String profileId,
            UUID actor,
            String sessionId,
            UUID requestId,
            String question,
            ToolInvocationContext context,
            Consumer<AgentEvent> events) {
        State captured = state.get();
        ClientGuideRuntime runtime;
        try {
            runtime = runtime(captured, profileId);
        } catch (GuideModelProfileException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return runtime.ask(actor, sessionId, requestId, question, context, events);
    }

    @Override
    public boolean cancel(UUID actor, String sessionId) {
        return sessions.cancel(new AgentSessionKey(actor, sessionId));
    }

    @Override
    public void clearSession(UUID actor, String sessionId) {
        sessions.clear(new AgentSessionKey(actor, sessionId));
    }

    @Override
    public void clearActor(UUID actor) {
        sessions.clearActor(actor);
    }

    @Override
    public void hydrateSession(
            UUID actor,
            String sessionId,
            List<GuideMessage> messages,
            List<ContextCheckpoint> checkpoints) {
        List<ModelMessage> history = messages.stream()
                .map(message -> new ModelMessage(
                        message.role() == GuideMessage.Role.USER
                                ? ModelRole.USER : ModelRole.ASSISTANT,
                        List.of(new ModelContent.Text(message.text()))))
                .toList();
        sessions.hydrate(new AgentSessionKey(actor, sessionId), history, checkpoints);
    }

    @Override
    public void hydrateContext(
            UUID actor,
            String sessionId,
            List<ModelMessage> messages,
            List<ContextCheckpoint> checkpoints) {
        sessions.hydrate(new AgentSessionKey(actor, sessionId), messages, checkpoints);
    }

    private State build(
            ModelProfilesConfigLoader.Load load,
            Function<ResolvedModelProfile, ModelClient> factory) {
        Objects.requireNonNull(load, "load");
        List<GuideClientModelProfile> summaries = new ArrayList<>();
        Map<String, ClientGuideRuntime> runtimes = new LinkedHashMap<>();
        for (ResolvedModelProfile profile : load.profiles()) {
            GuideFailure failure = profile.failure();
            summaries.add(new GuideClientModelProfile(
                    profile.definition().id(),
                    profile.definition().displayName(),
                    profile.definition().enabled(),
                    profile.available(),
                    profile.canonicalModelId(),
                    failure));
            if (profile.available()) {
                ModelClient model = Objects.requireNonNull(
                        factory.apply(profile), "model factory result");
                runtimes.put(profile.definition().id(), new ClientGuideRuntime(
                        model,
                        sessions,
                        gson,
                        dispatcher,
                        extension,
                        new LiveTraceStore(
                                null,
                                Set.of(profile.runtimeConfig().apiKey().reveal())),
                        profile.runtimeConfig().contextBudget(),
                        profile.canonicalModelId(),
                        capabilities.get()));
            }
        }
        return new State(load.config().defaultProfileId(), summaries, runtimes);
    }

    private static ClientCapabilitySnapshot resolveDefaultCapabilities(OpenAllayRuntime runtime) {
        ToolResult<ClientCapabilitySnapshot> resolved = new ClientCapabilityResolver().resolve(
                CapabilityPolicy.defaults(), runtime.tools().registrations(), runtime.skills());
        if (resolved instanceof ToolResult.Success<ClientCapabilitySnapshot> success) {
            return success.value();
        }
        ToolResult.Failure<ClientCapabilitySnapshot> failure =
                (ToolResult.Failure<ClientCapabilitySnapshot>) resolved;
        throw new IllegalStateException(failure.code() + ": " + failure.message());
    }

    private static ClientGuideRuntime runtime(State state, String profileId) {
        GuideClientModelProfile profile = state.profiles().stream()
                .filter(value -> value.id().equals(profileId))
                .findFirst()
                .orElseThrow(() -> new GuideModelProfileException(
                        "model_not_configured", "The selected client model profile does not exist"));
        if (!profile.available()) {
            throw new GuideModelProfileException(profile.failure().code(), profile.failure().message());
        }
        ClientGuideRuntime runtime = state.runtimes().get(profileId);
        if (runtime == null) {
            throw new GuideModelProfileException(
                    "invalid_model_config", "The selected client model runtime is unavailable");
        }
        return runtime;
    }

    private record State(
            String defaultProfileId,
            List<GuideClientModelProfile> profiles,
            Map<String, ClientGuideRuntime> runtimes) {
        private State {
            if (defaultProfileId == null || defaultProfileId.isBlank()) {
                throw new IllegalArgumentException("defaultProfileId must not be blank");
            }
            profiles = List.copyOf(profiles);
            runtimes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(runtimes));
        }
    }

    /** Fully built replacement whose publication is one non-failing atomic state swap. */
    public static final class PreparedReplacement {
        private final ClientModelRuntimeRegistry owner;
        private final State replacement;
        private final AtomicBoolean published = new AtomicBoolean();

        private PreparedReplacement(
                ClientModelRuntimeRegistry owner,
                State replacement) {
            this.owner = owner;
            this.replacement = replacement;
        }

        public void publish() {
            if (!published.compareAndSet(false, true)) {
                throw new IllegalStateException("Prepared model replacement was already published");
            }
            owner.state.set(replacement);
        }
    }
}
