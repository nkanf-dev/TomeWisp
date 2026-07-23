package dev.openallay.model.live;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.AgentRequest;
import dev.openallay.agent.AgentResult;
import dev.openallay.agent.GameGuideAgent;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.agent.trace.LiveTraceJson;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ModelToolDefinition;
import dev.openallay.model.anthropic.AnthropicMessagesClient;
import dev.openallay.model.config.ModelConfig;
import dev.openallay.model.config.ModelProtocol;
import dev.openallay.model.config.SecretValue;
import dev.openallay.model.openai.OpenAiChatClient;
import dev.openallay.model.scheduling.ModelRequestScheduler;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class LiveModelAcceptanceTest {
    @Test
    void realProviderStreamsCallsToolContinuesAndKeepsSecretOutOfTrace() throws Exception {
        Map<String, String> env = System.getenv();
        Assumptions.assumeTrue(Boolean.parseBoolean(env.get("OPENALLAY_LIVE_MODEL")));
        String baseUrl = required(env, "OPENALLAY_MODEL_BASE_URL");
        String modelId = required(env, "OPENALLAY_MODEL");
        String apiKey = required(env, "OPENALLAY_API_KEY");
        ModelProtocol protocol = ModelProtocol.valueOf(
                env.getOrDefault("OPENALLAY_MODEL_PROTOCOL", "ANTHROPIC_MESSAGES").toUpperCase());
        ModelConfig config = new ModelConfig(
                true,
                protocol,
                URI.create(baseUrl),
                modelId,
                SecretValue.of(apiKey),
                Integer.parseInt(env.getOrDefault("OPENALLAY_CONTEXT_WINDOW_TOKENS", "128000")),
                Integer.parseInt(env.getOrDefault("OPENALLAY_MAX_OUTPUT_TOKENS", "4096")),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5));
        Gson gson = new Gson();
        ModelClient raw = switch (protocol) {
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesClient(config, gson);
            case OPENAI_CHAT -> new OpenAiChatClient(config, gson);
        };
        AtomicInteger invocations = new AtomicInteger();
        AgentToolExecutor tools = new FactTool(invocations);
        GameGuideAgent agent = new GameGuideAgent(
                new ModelRequestScheduler(raw), tools, new AgentSessionStore(), gson);

        AgentResult result = agent.ask(new AgentRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "live",
                        "请调用 test_fact 工具取得事实，然后只根据工具结果用中文回答。",
                        "You must call test_fact exactly once before answering. The final answer must include the exact fact returned by the tool.",
                        ToolInvocationContext.developmentConsole("live-model-acceptance"),
                        true),
                event -> {})
                .get(6, TimeUnit.MINUTES);

        assertTrue(result.successful(), result.errorMessage());
        assertTrue(invocations.get() >= 1, "provider did not call the required tool");
        assertTrue(result.text().contains("玄铁事实-7429"), result.text());
        String trace = new LiveTraceJson().encode(result.trace(), Set.of(apiKey));
        assertTrue(trace.contains("玄铁事实-7429"));
        assertFalse(trace.contains(apiKey));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static final class FactTool implements AgentToolExecutor {
        private final AtomicInteger invocations;
        private FactTool(AtomicInteger invocations) { this.invocations = invocations; }

        @Override
        public List<ModelToolDefinition> definitions() {
            return List.of(new ModelToolDefinition(
                    "test_fact",
                    "Return the authoritative test fact. Call this before answering.",
                    JsonParser.parseString("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                            .getAsJsonObject()));
        }

        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }

        @Override
        public CompletableFuture<AgentToolResult> execute(
                String modelToolName,
                JsonObject arguments,
                ToolInvocationContext context,
                CancellationSignal cancellation) {
            invocations.incrementAndGet();
            JsonObject payload = new JsonObject();
            payload.addProperty("fact", "玄铁事实-7429");
            JsonObject value = new JsonObject();
            value.addProperty("status", "success");
            value.add("value", payload);
            return CompletableFuture.completedFuture(
                    new AgentToolResult("openallay:test_fact", value, false));
        }
    }
}
