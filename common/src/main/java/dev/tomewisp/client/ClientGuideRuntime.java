package dev.tomewisp.client;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentRequest;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.GameGuideAgent;
import dev.tomewisp.agent.session.AgentSessionKey;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.tool.LocalAgentToolExecutor;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.anthropic.AnthropicMessagesClient;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.config.ModelConfigLoader;
import dev.tomewisp.model.openai.OpenAiChatClient;
import dev.tomewisp.model.scheduling.ModelRequestScheduler;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ClientGuideRuntime {
    private final TomeWispRuntime runtime;
    private final AgentSessionStore sessions;
    private final GameGuideAgent agent;
    private final LocalAgentToolExecutor toolExecutor;
    private final ClientEventDispatcher dispatcher;
    private final Map<UUID, String> selectedSessions = new ConcurrentHashMap<>();

    public ClientGuideRuntime(
            TomeWispRuntime runtime,
            ModelClient model,
            AgentSessionStore sessions,
            Gson gson,
            ClientEventDispatcher dispatcher) {
        this.runtime = runtime;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        toolExecutor = new LocalAgentToolExecutor(runtime.tools(), gson);
        agent = new GameGuideAgent(new ModelRequestScheduler(model), toolExecutor, sessions, gson);
    }

    public static ToolResult<ClientGuideRuntime> create(
            TomeWispRuntime runtime,
            Path configPath,
            Map<String, String> environment,
            ClientEventDispatcher dispatcher) {
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
                runtime, model, new AgentSessionStore(), gson, dispatcher));
    }

    public Set<dev.tomewisp.context.ContextCapability> requiredContext() {
        return toolExecutor.requiredContext();
    }

    public CompletableFuture<AgentResult> ask(
            UUID actor,
            String question,
            ToolInvocationContext context,
            Consumer<AgentEvent> events) {
        String session = selectedSession(actor);
        AgentRequest request = new AgentRequest(
                UUID.randomUUID(),
                actor,
                session,
                question,
                systemPrompt(),
                context,
                true);
        return agent.ask(request, event -> dispatcher.execute(() -> events.accept(event)));
    }

    public String selectedSession(UUID actor) {
        return selectedSessions.computeIfAbsent(actor, ignored -> "main");
    }

    public void selectSession(UUID actor, String sessionId) {
        new AgentSessionKey(actor, sessionId);
        selectedSessions.put(actor, sessionId);
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
        return sessions.cancel(new AgentSessionKey(actor, selectedSession(actor)));
    }

    public void clearActor(UUID actor) {
        sessions.clearActor(actor);
        selectedSessions.remove(actor);
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
