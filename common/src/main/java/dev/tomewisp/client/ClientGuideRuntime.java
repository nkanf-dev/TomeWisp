package dev.tomewisp.client;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentRequest;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.GameGuideAgent;
import dev.tomewisp.agent.context.ContextBudget;
import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.agent.context.ContextCompactor;
import dev.tomewisp.agent.context.ToolResultContextReducer;
import dev.tomewisp.agent.context.Utf8ContextTokenEstimator;
import dev.tomewisp.agent.session.AgentSessionKey;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.trace.LiveTraceStore;
import dev.tomewisp.agent.tool.AgentToolExecutor;
import dev.tomewisp.agent.tool.CompositeAgentToolExecutor;
import dev.tomewisp.agent.tool.LocalAgentToolExecutor;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.guide.GuideLocalEndpoint;
import dev.tomewisp.guide.GuideMessage;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRole;
import dev.tomewisp.model.anthropic.AnthropicMessagesClient;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.config.ModelConfigLoader;
import dev.tomewisp.model.openai.OpenAiChatClient;
import dev.tomewisp.model.scheduling.ModelRequestScheduler;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ClientGuideRuntime implements GuideLocalEndpoint {
    private final TomeWispRuntime runtime;
    private final AgentSessionStore sessions;
    private final GameGuideAgent agent;
    private final AgentToolExecutor toolExecutor;
    private final ClientEventDispatcher dispatcher;
    private final LiveTraceStore traces;
    private final Map<UUID, String> selectedSessions = new ConcurrentHashMap<>();

    public ClientGuideRuntime(
            TomeWispRuntime runtime,
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher) {
        this(runtime, model, sessions, gson, dispatcher, null, new LiveTraceStore(null, Set.of()), null, null);
    }

    public ClientGuideRuntime(
            TomeWispRuntime runtime,
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension) {
        this(runtime, model, sessions, gson, dispatcher, extension, new LiveTraceStore(null, Set.of()), null, null);
    }

    ClientGuideRuntime(
            TomeWispRuntime runtime,
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher,
            AgentToolExecutor extension,
            LiveTraceStore traces,
            ContextBudget contextBudget,
            String modelIdentifier) {
        this.runtime = runtime;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.traces = traces;
        LocalAgentToolExecutor local = new LocalAgentToolExecutor(runtime.tools(), gson);
        toolExecutor = extension == null
                ? local
                : new CompositeAgentToolExecutor(List.of(local, extension));
        ModelRequestScheduler scheduled = new ModelRequestScheduler(model);
        ContextCompactor compactor = contextBudget == null ? null : new ContextCompactor(
                scheduled, gson, new Utf8ContextTokenEstimator(), new ToolResultContextReducer(),
                contextBudget, modelIdentifier, Clock.systemUTC());
        agent = new GameGuideAgent(scheduled, toolExecutor, sessions, gson, compactor);
    }

    public static ToolResult<ClientGuideRuntime> create(
            TomeWispRuntime runtime,
            Path configPath,
            Map<String, String> environment,
            ClientEventDispatcher dispatcher) {
        return create(runtime, configPath, environment, dispatcher, null);
    }

    public static ToolResult<ClientGuideRuntime> create(
            TomeWispRuntime runtime,
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

    public Set<dev.tomewisp.context.ContextCapability> requiredContext() {
        return toolExecutor.requiredContext();
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
        AgentRequest request = new AgentRequest(
                requestId,
                actor,
                session,
                question,
                systemPrompt(),
                context,
                true);
        return agent.ask(request, event -> dispatcher.execute(() -> events.accept(event)))
                .thenApply(result -> {
                    if (result.trace() != null) {
                        traces.record(result.trace());
                    }
                    return result;
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
    }

    public void clearActor(UUID actor) {
        sessions.clearActor(actor);
        selectedSessions.remove(actor);
    }

    public LiveTraceStore traces() {
        return traces;
    }

    private String systemPrompt() {
        return """
                You are TomeWisp, an in-game modded Minecraft guide. Use tools for pack-specific facts.
                Never claim unavailable data exists. Cite source IDs and provenance in factual answers.
                Load a Skill when its metadata matches the request, then follow its workflow.

                %s
                """.formatted(runtime.skills().metadataPrompt());
    }
}
