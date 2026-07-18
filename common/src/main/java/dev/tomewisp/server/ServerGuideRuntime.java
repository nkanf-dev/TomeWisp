package dev.tomewisp.server;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.GameGuideAgent;
import dev.tomewisp.agent.context.ContextCompactor;
import dev.tomewisp.agent.context.ToolResultContextReducer;
import dev.tomewisp.agent.context.Utf8ContextTokenEstimator;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.tool.LocalAgentToolExecutor;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.anthropic.AnthropicMessagesClient;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.config.ModelConfigLoader;
import dev.tomewisp.model.openai.OpenAiChatClient;
import dev.tomewisp.model.scheduling.ModelRequestScheduler;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Path;
import java.util.Map;
import java.time.Clock;

public record ServerGuideRuntime(ModelConfig config, ServerAgentService service) {
    public static ToolResult<ServerGuideRuntime> create(
            TomeWispRuntime runtime,
            Path configPath,
            Map<String, String> environment,
            ServerAgentService.ContextProvider contexts,
            ServerGuideEvents events) {
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
        GameGuideAgent agent = new GameGuideAgent(scheduled, tools, sessions, gson, compactor);
        String prompt = "You are TomeWisp's server-hosted Minecraft guide. Use only authorized "
                + "read tools and visible evidence. Never expose credentials.\n\n"
                + runtime.skills().metadataPrompt();
        ServerAgentService service = new ServerAgentService(
                agent, tools, sessions, contexts, events, gson, prompt, scheduled::awaitReady);
        return new ToolResult.Success<>(new ServerGuideRuntime(config, service));
    }
}
