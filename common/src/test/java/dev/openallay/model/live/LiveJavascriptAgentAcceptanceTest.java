package dev.openallay.model.live;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.openallay.agent.AgentRequest;
import dev.openallay.agent.AgentResult;
import dev.openallay.agent.AgentSystemPrompt;
import dev.openallay.agent.GameGuideAgent;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.tool.AgentToolExecutor;
import dev.openallay.agent.tool.AgentToolResult;
import dev.openallay.agent.tool.LocalAgentToolExecutor;
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
import dev.openallay.script.RhinoJavascriptRuntime;
import dev.openallay.script.data.MinecraftAgentHostGraph;
import dev.openallay.script.workspace.AgentResultWorkspaceRegistry;
import dev.openallay.script.workspace.JavascriptResultPresenter;
import dev.openallay.skill.BundledSkillLoader;
import dev.openallay.skill.LoadSkillTool;
import dev.openallay.skill.SkillParser;
import dev.openallay.skill.SkillRepository;
import dev.openallay.testing.JavascriptAgentTestFixtures;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.builtin.CalculateCraftabilityTool;
import dev.openallay.tool.builtin.RunJavascriptTool;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in, billable provider acceptance for the two set-level tasks that motivated the Rhino
 * runtime. It exercises the production prompt, bundled Skill, model codecs, Agent loop, compact
 * model-facing Tool result, and the actual isolated JavaScript engine.
 */
final class LiveJavascriptAgentAcceptanceTest {
    @Test
    void realProviderSolvesTwoBatchAnalysisTasksWithoutPerRowToolLoops() throws Exception {
        Map<String, String> environment = System.getenv();
        Assumptions.assumeTrue(Boolean.parseBoolean(
                environment.get("OPENALLAY_LIVE_JAVASCRIPT_AGENT")));
        Gson gson = new Gson();
        ModelClient raw = model(environment, gson);
        boolean stream = Boolean.parseBoolean(
                environment.getOrDefault("OPENALLAY_LIVE_STREAM", "true"));
        String scenario = environment.getOrDefault(
                "OPENALLAY_LIVE_JAVASCRIPT_SCENARIO", "all");
        Assumptions.assumeTrue(
                Set.of("all", "sword", "container").contains(scenario),
                "OPENALLAY_LIVE_JAVASCRIPT_SCENARIO must be all, sword, or container");

        ToolRegistry registry = new ToolRegistry();
        registry.register("openallay:javascript-acceptance", List.of(
                new RunJavascriptTool(
                        new RhinoJavascriptRuntime(),
                        MinecraftAgentHostGraph::new,
                        new AgentResultWorkspaceRegistry(),
                        new JavascriptResultPresenter()),
                new CalculateCraftabilityTool()));
        SkillRepository skills = new SkillRepository(
                new SkillParser(),
                registry.descriptors().stream().map(descriptor -> descriptor.id()).toList());
        assertTrue(skills.reload(new BundledSkillLoader().load(), Set.of()));
        registry.register("openallay:skills", List.of(new LoadSkillTool(skills)));

        AtomicInteger javascriptCalls = new AtomicInteger();
        List<String> callLog = new CopyOnWriteArrayList<>();
        CountingExecutor tools = new CountingExecutor(
                new LocalAgentToolExecutor(registry, gson), javascriptCalls, callLog);
        GameGuideAgent agent = new GameGuideAgent(
                new ModelRequestScheduler(raw), tools, new AgentSessionStore(), gson);
        String systemPrompt = AgentSystemPrompt.compose(skills.metadataPrompt());

        int swordCalls = 0;
        if (!"container".equals(scenario)) {
            int beforeSword = javascriptCalls.get();
            AgentResult sword = ask(
                    agent,
                    systemPrompt,
                    "live-js-sword",
                    stream,
                    """
                    请根据当前捕获的完整游戏数据，找出基础攻击伤害最高的剑。
                    必须比较所有候选剑，最后明确给出物品 ID 和伤害值。
                    """);
            swordCalls = javascriptCalls.get() - beforeSword;
            assertTrue(sword.successful(), sword.errorMessage());
            assertTrue(sword.text().contains(JavascriptAgentTestFixtures.HIGHEST_DAMAGE_SWORD),
                    sword.text());
            callLog.forEach(line -> System.out.println("OPENALLAY_LIVE_CALL " + line));
            assertTrue(swordCalls >= 1 && swordCalls <= 2,
                    "highest sword used " + swordCalls + " JavaScript calls: " + callLog);
        }

        int swordLogSize = callLog.size();
        int containerCalls = 0;
        if (!"sword".equals(scenario)) {
            int beforeContainer = javascriptCalls.get();
            AgentResult container = ask(
                    agent,
                    systemPrompt,
                    "live-js-container",
                    stream,
                    """
                    请根据当前捕获的完整游戏数据，找出可合成容器中消耗材料单位总数最少的配方。
                    必须比较所有容器配方，最后明确给出输出物品 ID、配方 ID 和材料单位数。
                    """);
            containerCalls = javascriptCalls.get() - beforeContainer;
            assertTrue(container.successful(), container.errorMessage());
            assertTrue(container.text().contains(JavascriptAgentTestFixtures.LEAST_MATERIAL_CONTAINER),
                    container.text());
            callLog.subList(swordLogSize, callLog.size())
                    .forEach(line -> System.out.println("OPENALLAY_LIVE_CALL " + line));
            assertTrue(containerCalls >= 1 && containerCalls <= 2,
                    "least-material container used " + containerCalls + " JavaScript calls");
        }

        System.out.println("OPENALLAY_LIVE_JAVASCRIPT_AGENT code=success"
                + " scenario=" + scenario
                + " sword_calls=" + swordCalls
                + " container_calls=" + containerCalls);
    }

    private static AgentResult ask(
            GameGuideAgent agent,
            String systemPrompt,
            String sessionId,
            boolean stream,
            String message)
            throws Exception {
        String correlationId = "acceptance-" + sessionId;
        return agent.ask(
                        new AgentRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                sessionId,
                                message,
                                systemPrompt,
                                JavascriptAgentTestFixtures.context(correlationId),
                                stream),
                        event -> {})
                .get(6, TimeUnit.MINUTES);
    }

    private static ModelClient model(Map<String, String> environment, Gson gson) {
        String baseUrl = required(environment, "OPENALLAY_MODEL_BASE_URL");
        String modelId = required(environment, "OPENALLAY_MODEL");
        String apiKey = required(environment, "OPENALLAY_API_KEY");
        ModelProtocol protocol = ModelProtocol.valueOf(environment
                .getOrDefault("OPENALLAY_MODEL_PROTOCOL", "OPENAI_CHAT")
                .toUpperCase());
        ModelConfig config = new ModelConfig(
                true,
                protocol,
                URI.create(baseUrl),
                modelId,
                SecretValue.of(apiKey),
                Integer.parseInt(environment.getOrDefault(
                        "OPENALLAY_CONTEXT_WINDOW_TOKENS", "100000")),
                Integer.parseInt(environment.getOrDefault(
                        "OPENALLAY_MAX_OUTPUT_TOKENS", "8192")),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5));
        return switch (protocol) {
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesClient(config, gson);
            case OPENAI_CHAT -> new OpenAiChatClient(config, gson);
        };
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private record CountingExecutor(
            AgentToolExecutor delegate,
            AtomicInteger javascriptCalls,
            List<String> callLog)
            implements AgentToolExecutor {
        @Override
        public List<ModelToolDefinition> definitions() {
            return delegate.definitions();
        }

        @Override
        public Set<ContextCapability> requiredContext() {
            return delegate.requiredContext();
        }

        @Override
        public java.util.Optional<String> canonicalToolId(String modelToolName) {
            return delegate.canonicalToolId(modelToolName);
        }

        @Override
        public CompletableFuture<AgentToolResult> execute(
                String modelToolName,
                JsonObject arguments,
                ToolInvocationContext context,
                CancellationSignal cancellation) {
            String toolId = delegate.canonicalToolId(modelToolName).orElse(modelToolName);
            if ("openallay:run_javascript".equals(toolId)) {
                int call = javascriptCalls.incrementAndGet();
                callLog.add("call=" + call + " source="
                        + compact(arguments.has("source")
                                ? arguments.get("source").getAsString()
                                : "<missing>"));
            } else {
                callLog.add("tool=" + toolId + " arguments=" + compact(arguments.toString()));
            }
            return delegate.execute(modelToolName, arguments, context, cancellation)
                    .thenApply(result -> {
                        callLog.add("tool_result=" + result.toolId()
                                + " value=" + compact(result.modelValue().getAsString()));
                        return result;
                    });
        }

        @Override
        public void closeRequestScope(String correlationId) {
            delegate.closeRequestScope(correlationId);
        }

        private static String compact(String value) {
            String oneLine = value.replaceAll("\\s+", " ").strip();
            return oneLine.length() <= 800 ? oneLine : oneLine.substring(0, 800) + "…";
        }
    }
}
