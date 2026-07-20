package dev.openallay.server;

import com.google.gson.Gson;
import dev.openallay.OpenAllayRuntime;
import dev.openallay.agent.GameGuideAgent;
import dev.openallay.agent.context.ContextCompactor;
import dev.openallay.agent.context.ToolResultContextReducer;
import dev.openallay.agent.context.Utf8ContextTokenEstimator;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.tool.LocalAgentToolExecutor;
import dev.openallay.bridge.server.PlayerClientToolRouter;
import dev.openallay.model.ModelClient;
import dev.openallay.model.anthropic.AnthropicMessagesClient;
import dev.openallay.model.config.ModelConfig;
import dev.openallay.model.config.ModelConfigLoader;
import dev.openallay.model.openai.OpenAiChatClient;
import dev.openallay.model.scheduling.ModelRequestScheduler;
import dev.openallay.tool.ToolResult;
import java.nio.file.Path;
import java.util.Map;
import java.time.Clock;

public record ServerGuideRuntime(
        ModelConfig config,
        ServerAgentService service,
        dev.openallay.guide.GuideContextSpec contextSpec,
        PlayerClientToolRouter clientTools,
        dev.openallay.resource.runtime.ResourceRequestRegistry resources) {
    public static ToolResult<ServerGuideRuntime> create(
            OpenAllayRuntime runtime,
            Path configPath,
            Map<String, String> environment,
            ServerAgentService.ContextProvider contexts,
            ServerGuideEvents events) {
        return create(
                runtime,
                configPath,
                environment,
                contexts,
                events,
                new PlayerClientToolRouter.Transport() {
                    @Override
                    public boolean call(
                            java.util.UUID actorId,
                            dev.openallay.bridge.protocol.ClientToolCallPayload payload) {
                        return false;
                    }

                    @Override
                    public void cancel(
                            java.util.UUID actorId,
                            dev.openallay.bridge.protocol.ClientToolCancelPayload payload) {}
                });
    }

    public static ToolResult<ServerGuideRuntime> create(
            OpenAllayRuntime runtime,
            Path configPath,
            Map<String, String> environment,
            ServerAgentService.ContextProvider contexts,
            ServerGuideEvents events,
            PlayerClientToolRouter.Transport clientToolTransport) {
        ToolResult<ModelConfig> loaded = new ModelConfigLoader().load(configPath, environment);
        if (loaded instanceof ToolResult.Failure<ModelConfig> failure) {
            return new ToolResult.Failure<>(failure.code(), failure.message());
        }
        ModelConfig config = ((ToolResult.Success<ModelConfig>) loaded).value();
        if (!config.enabled()) {
            return new ToolResult.Failure<>("model_disabled", "Server model is disabled");
        }
        Gson gson = new Gson();
        ModelClient raw = switch (config.protocol()) {
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesClient(config, gson);
            case OPENAI_CHAT -> new OpenAiChatClient(config, gson);
        };
        ModelRequestScheduler scheduled = new ModelRequestScheduler(raw);
        LocalAgentToolExecutor tools = new LocalAgentToolExecutor(runtime.tools(), gson);
        AgentSessionStore sessions = new AgentSessionStore();
        ContextCompactor compactor = new ContextCompactor(
                scheduled, gson, new Utf8ContextTokenEstimator(), new ToolResultContextReducer(),
                config.contextBudget(), config.model(), Clock.systemUTC());
        PlayerClientToolRouter clientTools = new PlayerClientToolRouter(
                runtime.tools(),
                gson,
                clientToolTransport,
                config.requestTimeout(),
                runtime.resources());
        String prompt = dev.openallay.agent.AgentSystemPrompt.compose(
                runtime.skills().metadataPrompt());
        int promptAndTools = new Utf8ContextTokenEstimator().estimate(
                prompt, java.util.List.of(), tools.definitions());
        dev.openallay.guide.GuideContextSpec contextSpec =
                new dev.openallay.guide.GuideContextSpec(
                        config.contextBudget(), promptAndTools, config.model());
        ServerAgentService service = new ServerAgentService(
                (actor, payload) -> {
                    ToolResult<dev.openallay.agent.tool.AgentToolExecutor> opened = clientTools.open(
                            actor,
                            payload.requestId(),
                            payload.sessionId(),
                            payload.clientToolIds());
                    if (opened instanceof ToolResult.Failure<dev.openallay.agent.tool.AgentToolExecutor>
                            failure) {
                        return new ToolResult.Failure<>(failure.code(), failure.message());
                    }
                    dev.openallay.agent.tool.AgentToolExecutor requestTools =
                            ((ToolResult.Success<dev.openallay.agent.tool.AgentToolExecutor>) opened)
                                    .value();
                    GameGuideAgent agent = new GameGuideAgent(
                            scheduled, requestTools, sessions, gson, compactor);
                    java.util.concurrent.atomic.AtomicReference<
                                    dev.openallay.resource.runtime.ResourceRequestRegistry.RequestHandle>
                            resourceHandle = new java.util.concurrent.atomic.AtomicReference<>();
                    return new ToolResult.Success<>(new ServerAgentService.RequestRuntime(
                            agent,
                            requestTools,
                            context -> resourceHandle.set(runtime.resources().open(
                                    actor,
                                    payload.sessionId(),
                                    payload.requestId(),
                                    runtime.resources().connectionGeneration(actor),
                                    "server",
                                    requestTools.definitions().stream()
                                            .map(dev.openallay.model.ModelToolDefinition::name)
                                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                                    contextSpec.budget(),
                                    context)),
                            () -> {
                                var handle = resourceHandle.getAndSet(null);
                                if (handle != null) {
                                    handle.close();
                                }
                                clientTools.close(actor, payload.requestId());
                            }));
                },
                sessions,
                contexts,
                events,
                gson,
                prompt,
                scheduled::awaitReady);
        return new ToolResult.Success<>(
                new ServerGuideRuntime(config, service, contextSpec, clientTools, runtime.resources()));
    }
}
