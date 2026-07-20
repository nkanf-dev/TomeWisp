package dev.openallay.client;

import com.google.gson.Gson;
import dev.openallay.OpenAllayRuntime;
import dev.openallay.agent.AgentEvent;
import dev.openallay.agent.AgentRequest;
import dev.openallay.agent.AgentResult;
import dev.openallay.agent.GameGuideAgent;
import dev.openallay.agent.context.ContextBudget;
import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.agent.context.ContextCompactor;
import dev.openallay.agent.context.ToolResultContextReducer;
import dev.openallay.agent.context.Utf8ContextTokenEstimator;
import dev.openallay.agent.session.AgentSessionKey;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.trace.LiveTraceStore;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.agent.tool.CompositeAgentToolExecutor;
import dev.openallay.agent.tool.LocalAgentToolExecutor;
import dev.openallay.bridge.client.ClientPlacedToolExecutor;
import dev.openallay.bridge.client.RemoteToolExecutor;
import dev.openallay.capability.CapabilityPolicy;
import dev.openallay.capability.ClientCapabilityResolver;
import dev.openallay.capability.ClientCapabilitySnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.guide.GuideLocalEndpoint;
import dev.openallay.guide.GuideContextSpec;
import dev.openallay.guide.GuideMessage;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ModelContent;
import dev.openallay.model.ModelMessage;
import dev.openallay.model.ModelRole;
import dev.openallay.model.anthropic.AnthropicMessagesClient;
import dev.openallay.model.config.ModelConfig;
import dev.openallay.model.config.ModelConfigLoader;
import dev.openallay.model.openai.OpenAiChatClient;
import dev.openallay.model.scheduling.ModelRequestScheduler;
import dev.openallay.tool.ToolResult;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ClientGuideRuntime implements GuideLocalEndpoint {
    private final EndpointRuntime endpoint;
    private final AgentSessionStore sessions;
    private final GameGuideAgent agent;
    private final AgentToolExecutor toolExecutor;
    private final ClientEventDispatcher dispatcher;
    private final LiveTraceStore traces;
    private final ClientCapabilitySnapshot capabilities;
    private final Gson gson;
    private final AgentToolExecutor extension;
    private final Map<UUID, String> selectedSessions;
    private final ResourceRequestRegistry resources;

    public ClientGuideRuntime(
            OpenAllayRuntime runtime,
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher) {
        this(
                model,
                sessions,
                gson,
                dispatcher,
                null,
                new LiveTraceStore(null, Set.of()),
                null,
                null,
                defaultCapabilities(runtime),
                null);
    }

    public ClientGuideRuntime(
            OpenAllayRuntime runtime,
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension) {
        this(
                model,
                sessions,
                gson,
                dispatcher,
                extension,
                new LiveTraceStore(null, Set.of()),
                null,
                null,
                defaultCapabilities(runtime),
                null);
    }

    /** Explicit model metadata is required when the request-scoped Resource VFS is enabled. */
    public ClientGuideRuntime(
            OpenAllayRuntime runtime,
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher,
            ContextBudget contextBudget,
            String modelIdentifier) {
        this(runtime, model, sessions, gson, dispatcher, null,
                new LiveTraceStore(null, Set.of()), contextBudget, modelIdentifier);
    }

    ClientGuideRuntime(
            OpenAllayRuntime runtime,
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            LiveTraceStore traces,
            ContextBudget contextBudget,
            String modelIdentifier) {
        this(
                model,
                sessions,
                gson,
                dispatcher,
                extension,
                traces,
                contextBudget,
                modelIdentifier,
                defaultCapabilities(runtime),
                runtime.resources());
    }

    ClientGuideRuntime(
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            LiveTraceStore traces,
            ContextBudget contextBudget,
            String modelIdentifier,
            ClientCapabilitySnapshot capabilities) {
        this(
                model,
                sessions,
                gson,
                dispatcher,
                extension,
                traces,
                contextBudget,
                modelIdentifier,
                capabilities,
                null);
    }

    ClientGuideRuntime(
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            LiveTraceStore traces,
            ContextBudget contextBudget,
            String modelIdentifier,
            ClientCapabilitySnapshot capabilities,
            ResourceRequestRegistry resources) {
        this(
                endpoint(model, gson, contextBudget, modelIdentifier),
                sessions,
                gson,
                dispatcher,
                extension,
                traces,
                capabilities,
                new ConcurrentHashMap<>(),
                resources);
    }

    private ClientGuideRuntime(
            EndpointRuntime endpoint,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            LiveTraceStore traces,
            ClientCapabilitySnapshot capabilities,
            Map<UUID, String> selectedSessions,
            ResourceRequestRegistry resources) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.traces = Objects.requireNonNull(traces, "traces");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.extension = extension;
        this.selectedSessions = Objects.requireNonNull(selectedSessions, "selectedSessions");
        this.resources = resources;
        LocalAgentToolExecutor local = new LocalAgentToolExecutor(capabilities.localTools(), gson);
        toolExecutor = extension == null
                ? local
                : extension instanceof RemoteToolExecutor remote
                        ? new ClientPlacedToolExecutor(local, remote)
                        : new CompositeAgentToolExecutor(List.of(local, extension));
        agent = new GameGuideAgent(
                endpoint.scheduler(), toolExecutor, sessions, gson, endpoint.compactor());
    }

    ClientGuideRuntime withCapabilities(ClientCapabilitySnapshot replacement) {
        return new ClientGuideRuntime(
                endpoint,
                sessions,
                gson,
                dispatcher,
                extension,
                traces,
                replacement,
                selectedSessions,
                resources);
    }

    Object endpointIdentity() {
        return endpoint.scheduler();
    }

    public static ToolResult<ClientGuideRuntime> create(
            OpenAllayRuntime runtime,
            Path configPath,
            Map<String, String> environment,
            ClientEventDispatcher dispatcher) {
        return create(runtime, configPath, environment, dispatcher, null);
    }

    public static ToolResult<ClientGuideRuntime> create(
            OpenAllayRuntime runtime,
            Path configPath,
            Map<String, String> environment,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension) {
        ToolResult<ModelConfig> loaded = new ModelConfigLoader().load(configPath, environment);
        if (loaded instanceof ToolResult.Failure<ModelConfig> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        ModelConfig config = ((ToolResult.Success<ModelConfig>) loaded).value();
        if (!config.enabled()) {
            return new ToolResult.Failure<>("model_disabled", "Client model is disabled");
        }
        Gson gson = new Gson();
        ModelClient model = switch (config.protocol()) {
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesClient(config, gson);
            case OPENAI_CHAT -> new OpenAiChatClient(config, gson);
        };
        return new ToolResult.Success<>(new ClientGuideRuntime(
                runtime,
                model,
                new AgentSessionStore(),
                gson,
                dispatcher,
                extension,
                new LiveTraceStore(null, Set.of(config.apiKey().reveal())),
                config.contextBudget(),
                config.model()));
    }

    public Set<dev.openallay.context.ContextCapability> requiredContext() {
        return toolExecutor.requiredContext();
    }

    @Override
    public Optional<GuideContextSpec> contextSpec(String profileId) {
        if (endpoint.contextBudget() == null || endpoint.modelIdentifier() == null) {
            return Optional.empty();
        }
        int promptAndTools = new Utf8ContextTokenEstimator().estimate(
                systemPrompt(), List.of(), toolExecutor.definitions());
        if (promptAndTools >= endpoint.contextBudget().inputTokens()) {
            return Optional.empty();
        }
        return Optional.of(new GuideContextSpec(
                endpoint.contextBudget(), promptAndTools, endpoint.modelIdentifier()));
    }

    @Override
    public void hydrateContext(
            UUID actor,
            String sessionId,
            List<ModelMessage> messages,
            List<ContextCheckpoint> checkpoints) {
        sessions.hydrate(new AgentSessionKey(actor, sessionId), messages, checkpoints);
    }

    public CompletableFuture<AgentResult> ask(
            UUID actor,
            String question,
            ToolInvocationContext context,
            Consumer<AgentEvent> events) {
        return ask(
                actor,
                selectedSession(actor),
                UUID.randomUUID(),
                question,
                context,
                events);
    }

    @Override
    public CompletableFuture<AgentResult> ask(
            UUID actor,
            String session,
            UUID requestId,
            String question,
            ToolInvocationContext context,
            Consumer<AgentEvent> events) {
        ResourceRequestRegistry.RequestHandle resourceHandle = resources == null
                ? null
                : resources.open(
                        actor,
                        session,
                        requestId,
                        resources.connectionGeneration(actor),
                        "client",
                        capabilities.localTools().descriptors().stream()
                                .map(descriptor -> descriptor.id())
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        Objects.requireNonNull(endpoint.contextBudget(),
                                "Resource VFS requires the selected model context budget"),
                        context);
        AgentRequest request = new AgentRequest(
                requestId,
                actor,
                session,
                question,
                systemPrompt(),
                context,
                true);
        CompletableFuture<AgentResult> completion;
        try {
            completion = agent.ask(request, event -> dispatcher.execute(() -> events.accept(event)));
        } catch (RuntimeException failure) {
            if (resourceHandle != null) {
                resourceHandle.close();
            }
            throw failure;
        }
        return completion
                .thenApply(result -> {
                    if (result.trace() != null) {
                        traces.record(result.trace());
                    }
                    return result;
                })
                .whenComplete((ignored, failure) -> {
                    if (resourceHandle != null) {
                        resourceHandle.close();
                    }
                });
    }

    public String selectedSession(UUID actor) {
        return selectedSessions.computeIfAbsent(actor, ignored -> "main");
    }

    public void selectSession(UUID actor, String sessionId) {
        new AgentSessionKey(actor, sessionId);
        selectedSessions.put(actor, sessionId);
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
                                ? ModelRole.USER
                                : ModelRole.ASSISTANT,
                        List.of(new ModelContent.Text(message.text()))))
                .toList();
        sessions.hydrate(new AgentSessionKey(actor, sessionId), history, checkpoints);
    }

    public List<String> sessions(UUID actor) {
        java.util.TreeSet<String> ids = new java.util.TreeSet<>(sessions.sessions(actor).stream()
                .map(AgentSessionKey::sessionId).toList());
        ids.add(selectedSession(actor));
        return List.copyOf(ids);
    }

    public boolean closeSession(UUID actor, String sessionId) {
        boolean existed = sessions.sessions(actor).stream()
                .anyMatch(key -> key.sessionId().equals(sessionId));
        sessions.clear(new AgentSessionKey(actor, sessionId));
        if (resources != null) {
            resources.deleteSession(actor, sessionId, resources.connectionGeneration(actor));
        }
        if (selectedSession(actor).equals(sessionId)) {
            selectedSessions.put(actor, "main");
        }
        return existed;
    }

    public boolean cancel(UUID actor) {
        return cancel(actor, selectedSession(actor));
    }

    @Override
    public boolean cancel(UUID actor, String sessionId) {
        return sessions.cancel(new AgentSessionKey(actor, sessionId));
    }

    @Override
    public void clearSession(UUID actor, String sessionId) {
        sessions.clear(new AgentSessionKey(actor, sessionId));
        if (resources != null) {
            resources.deleteSession(actor, sessionId, resources.connectionGeneration(actor));
        }
    }

    public void clearActor(UUID actor) {
        if (resources != null) {
            resources.disconnectActor(actor);
        }
        sessions.clearActor(actor);
        selectedSessions.remove(actor);
    }

    public LiveTraceStore traces() {
        return traces;
    }

    private String systemPrompt() {
        return dev.openallay.agent.AgentSystemPrompt.compose(
                capabilities.skills().metadataPrompt());
    }

    private static EndpointRuntime endpoint(
            ModelClient model,
            Gson gson,
            ContextBudget contextBudget,
            String modelIdentifier) {
        ModelRequestScheduler scheduler = new ModelRequestScheduler(model);
        ContextCompactor compactor = contextBudget == null ? null : new ContextCompactor(
                scheduler,
                gson,
                new Utf8ContextTokenEstimator(),
                new ToolResultContextReducer(),
                contextBudget,
                modelIdentifier,
                Clock.systemUTC());
        return new EndpointRuntime(scheduler, compactor, contextBudget, modelIdentifier);
    }

    private static ClientCapabilitySnapshot defaultCapabilities(OpenAllayRuntime runtime) {
        ToolResult<ClientCapabilitySnapshot> resolved = new ClientCapabilityResolver().resolve(
                CapabilityPolicy.defaults(), runtime.tools().registrations(), runtime.skills());
        if (resolved instanceof ToolResult.Success<ClientCapabilitySnapshot> success) {
            return success.value();
        }
        ToolResult.Failure<ClientCapabilitySnapshot> failure =
                (ToolResult.Failure<ClientCapabilitySnapshot>) resolved;
        throw new IllegalStateException(failure.code() + ": " + failure.message());
    }

    private record EndpointRuntime(
            ModelRequestScheduler scheduler,
            ContextCompactor compactor,
            ContextBudget contextBudget,
            String modelIdentifier) {}
}
