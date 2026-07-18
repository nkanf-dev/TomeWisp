package dev.tomewisp.client;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.agent.session.AgentSessionKey;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.guide.GuideClientModelProfile;
import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.guide.GuideLocalEndpoint;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.guide.GuideModelProfileException;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRole;
import dev.tomewisp.model.anthropic.AnthropicMessagesClient;
import dev.tomewisp.model.config.ModelProfilesConfigLoader;
import dev.tomewisp.model.config.ResolvedModelProfile;
import dev.tomewisp.model.openai.OpenAiChatClient;
import dev.tomewisp.agent.trace.LiveTraceStore;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Atomic named-profile registry whose runtimes share provider-neutral sessions. */
public final class ClientModelRuntimeRegistry implements GuideLocalEndpoint {
    private final TomeWispRuntime productRuntime;
    private final Gson gson;
    private final ClientEventDispatcher dispatcher;
    private final AgentToolExecutor extension;
    private final Function<ResolvedModelProfile, ModelClient> modelFactory;
    private final AgentSessionStore sessions = new AgentSessionStore();
    private final AtomicReference<State> state = new AtomicReference<>();

    ClientModelRuntimeRegistry(
            TomeWispRuntime productRuntime,
            ModelProfilesConfigLoader.Load initial,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            Function<ResolvedModelProfile, ModelClient> modelFactory) {
        this.productRuntime = Objects.requireNonNull(productRuntime, "productRuntime");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.extension = extension;
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
        replace(initial, modelFactory);
    }

    public static ToolResult<ClientModelRuntimeRegistry> create(
            TomeWispRuntime runtime,
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
        Function<ResolvedModelProfile, ModelClient> factory = profile -> switch (
                profile.runtimeConfig().protocol()) {
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesClient(profile.runtimeConfig(), gson);
            case OPENAI_CHAT -> new OpenAiChatClient(profile.runtimeConfig(), gson);
        };
        return new ToolResult.Success<>(new ClientModelRuntimeRegistry(
                runtime, value, gson, dispatcher, extension, factory));
    }

    public synchronized void replace(
            ModelProfilesConfigLoader.Load replacement,
            Function<ResolvedModelProfile, ModelClient> replacementFactory) {
        Objects.requireNonNull(replacementFactory, "replacementFactory");
        state.set(build(replacement, replacementFactory));
    }

    public synchronized void replace(ModelProfilesConfigLoader.Load replacement) {
        state.set(build(replacement, modelFactory));
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
                    profile.definition().model(),
                    failure));
            if (profile.available()) {
                ModelClient model = Objects.requireNonNull(
                        factory.apply(profile), "model factory result");
                runtimes.put(profile.definition().id(), new ClientGuideRuntime(
                        productRuntime,
                        model,
                        sessions,
                        gson,
                        dispatcher,
                        extension,
                        new LiveTraceStore(
                                null,
                                Set.of(profile.runtimeConfig().apiKey().reveal())),
                        profile.runtimeConfig().contextBudget(),
                        profile.runtimeConfig().model()));
            }
        }
        return new State(load.config().defaultProfileId(), summaries, runtimes);
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
}
