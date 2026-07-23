package dev.openallay.model.live;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import dev.openallay.agent.AgentEvent;
import dev.openallay.agent.AgentRequest;
import dev.openallay.agent.AgentResult;
import dev.openallay.agent.AgentSystemPrompt;
import dev.openallay.agent.GameGuideAgent;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.tool.LocalAgentToolExecutor;
import dev.openallay.agent.trace.LiveTraceJson;
import dev.openallay.context.CallerKind;
import dev.openallay.context.CallerSnapshot;
import dev.openallay.context.ContextMetrics;
import dev.openallay.context.IngredientAlternativeSnapshot;
import dev.openallay.context.IngredientRequirementSnapshot;
import dev.openallay.context.ItemStackSnapshot;
import dev.openallay.context.RecipeEntrySnapshot;
import dev.openallay.context.RecipeLayoutSnapshot;
import dev.openallay.context.RecipeOutputSnapshot;
import dev.openallay.context.RecipeProcessingSnapshot;
import dev.openallay.context.RecipeReference;
import dev.openallay.context.RecipeSnapshot;
import dev.openallay.context.RegistryEntrySnapshot;
import dev.openallay.context.RegistrySnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.game.ObservableGameStateSnapshot;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.model.ModelClient;
import dev.openallay.model.ProviderModelClients;
import dev.openallay.model.config.ModelConfig;
import dev.openallay.model.config.ModelProtocol;
import dev.openallay.model.config.SecretValue;
import dev.openallay.model.scheduling.ModelRequestScheduler;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.platform.PlatformService;
import dev.openallay.resource.mod.ModResourceSnapshot;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import dev.openallay.testing.GroundedTestFixtures;
import dev.openallay.tool.ToolRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in live-provider smoke for the Resource VFS path. */
final class LiveResourceVfsAcceptanceTest {
    private static final Gson GSON = new Gson();

    @Test
    void realProviderUsesResourceVfsToAnswerGroundedQuestions() throws Exception {
        Map<String, String> env = System.getenv();
        Assumptions.assumeTrue(Boolean.parseBoolean(env.get("OPENALLAY_LIVE_VFS")));
        String baseUrl = required(env, "OPENALLAY_MODEL_BASE_URL");
        String modelId = required(env, "OPENALLAY_MODEL");
        String apiKey = required(env, "OPENALLAY_API_KEY");
        ModelProtocol protocol = ModelProtocol.valueOf(
                env.getOrDefault("OPENALLAY_MODEL_PROTOCOL", "OPENAI_CHAT").toUpperCase(Locale.ROOT));
        int contextWindow = Integer.parseInt(env.getOrDefault("OPENALLAY_CONTEXT_WINDOW_TOKENS", "100000"));
        int maxOutput = Integer.parseInt(env.getOrDefault("OPENALLAY_MAX_OUTPUT_TOKENS", "4096"));

        ModelConfig config = new ModelConfig(
                true,
                protocol,
                URI.create(baseUrl),
                modelId,
                SecretValue.of(apiKey),
                contextWindow,
                maxOutput,
                Duration.ofSeconds(30),
                Duration.ofMinutes(5));
        ModelClient client = ProviderModelClients.create(config, GSON);

        try (LiveRuntime runtime = LiveRuntime.open(contextWindow, maxOutput)) {
            GameGuideAgent agent = new GameGuideAgent(
                    new ModelRequestScheduler(client),
                    runtime.executor(),
                    new AgentSessionStore(),
                    GSON);
            String prompt = AgentSystemPrompt.compose("") + """

                    For this acceptance run:
                    - Prefer Resource VFS Tools only.
                    - Discover fields via /@schema before sorting unknown numeric properties.
                    - For swords, inspect item properties such as attack damage and compare them.
                    - For containers, inspect recipe mounts and prefer the container whose craft path needs the fewest recipes.
                    - Do not invent numeric values that tools already return.
                    - Answer in Chinese with the concrete item name and the decisive evidence value.
                    """;

            List<AgentEvent> swordEvents = new ArrayList<>();
            AgentResult sword = runtime.ask(
                    agent,
                    "当前游戏数据里伤害最高的剑是哪一把？请用 Resource VFS 查询后用中文回答剑名和伤害值。",
                    prompt,
                    swordEvents);

            assertTrue(sword.successful(), () -> "sword task failed: code=" + sword.errorCode()
                    + " message=" + sword.errorMessage()
                    + " events=" + summarize(swordEvents)
                    + " answer=" + sword.text());
            Set<String> swordTools = toolIds(swordEvents);
            assertTrue(swordTools.stream().anyMatch(id -> id.contains("resource_")),
                    () -> "expected resource_* tool use for sword, got " + swordTools
                            + " answer=" + sword.text());
            String swordAnswer = sword.text().toLowerCase(Locale.ROOT);
            assertTrue(
                    swordAnswer.contains("netherite")
                            || swordAnswer.contains("下界合金")
                            || swordAnswer.contains("netherite_sword"),
                    () -> "answer missing netherite sword: " + sword.text() + " tools=" + swordTools);
            assertTrue(
                    swordAnswer.contains("8")
                            || swordAnswer.contains("8.0")
                            || swordAnswer.contains("伤害"),
                    () -> "answer missing damage evidence: " + sword.text());

            List<AgentEvent> containerEvents = new ArrayList<>();
            AgentResult container = runtime.ask(
                    agent,
                    "当前游戏数据里，所需配方最少的容器是哪个？请比较箱子、木桶、漏斗的配方依赖，用 Resource VFS 后用中文回答容器名和配方数量依据。",
                    prompt,
                    containerEvents);

            assertTrue(container.successful(), () -> "container task failed: code=" + container.errorCode()
                    + " message=" + container.errorMessage()
                    + " events=" + summarize(containerEvents)
                    + " answer=" + container.text());
            Set<String> containerTools = toolIds(containerEvents);
            assertTrue(containerTools.stream().anyMatch(id -> id.contains("resource_")),
                    () -> "expected resource_* tool use for container, got " + containerTools
                            + " answer=" + container.text());
            String containerAnswer = container.text().toLowerCase(Locale.ROOT);
            assertTrue(
                    containerAnswer.contains("chest")
                            || containerAnswer.contains("箱子")
                            || containerAnswer.contains("minecraft:chest"),
                    () -> "answer missing chest as fewest-recipe container: " + container.text()
                            + " tools=" + containerTools);

            String swordTrace = new LiveTraceJson().encode(sword.trace(), Set.of(apiKey));
            String containerTrace = new LiveTraceJson().encode(container.trace(), Set.of(apiKey));
            assertFalse(swordTrace.contains(apiKey));
            assertFalse(containerTrace.contains(apiKey));

            System.out.println("OPENALLAY_LIVE_VFS code=success model=" + modelId
                    + " protocol=" + protocol
                    + " swordTools=" + swordTools
                    + " containerTools=" + containerTools
                    + " swordAnswerChars=" + sword.text().length()
                    + " containerAnswerChars=" + container.text().length());
            System.out.println("OPENALLAY_LIVE_VFS_SWORD_ANSWER " + sword.text().replace('\n', ' '));
            System.out.println("OPENALLAY_LIVE_VFS_CONTAINER_ANSWER " + container.text().replace('\n', ' '));
        }
    }

    private static Set<String> toolIds(List<AgentEvent> events) {
        return events.stream()
                .filter(AgentEvent.ToolStarted.class::isInstance)
                .map(event -> ((AgentEvent.ToolStarted) event).toolId())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String summarize(List<AgentEvent> events) {
        return events.stream()
                .map(event -> event.getClass().getSimpleName())
                .collect(Collectors.joining(","));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static final class LiveRuntime implements AutoCloseable {
        private final ResourceRequestRegistry resources;
        private final LocalAgentToolExecutor executor;
        private final ToolInvocationContext context;
        private final long connection;
        private final dev.openallay.agent.context.ContextBudget contextBudget;

        private LiveRuntime(
                ResourceRequestRegistry resources,
                LocalAgentToolExecutor executor,
                ToolInvocationContext context,
                long connection,
                dev.openallay.agent.context.ContextBudget contextBudget) {
            this.resources = resources;
            this.executor = executor;
            this.context = context;
            this.connection = connection;
            this.contextBudget = contextBudget;
        }

        static LiveRuntime open(int contextWindow, int maxOutput) {
            ToolInvocationContext context = buildContext("live-vfs");
            ResourceRequestRegistry resources = new ResourceRequestRegistry(
                    new TestPlatform(), new KnowledgeRegistry());
            long connection = resources.connectionGeneration(GroundedTestFixtures.PLAYER_ID);
            ToolRegistry tools = new ToolRegistry();
            tools.registerResourceTools("live:vfs", resources);
            return new LiveRuntime(
                    resources,
                    new LocalAgentToolExecutor(tools, GSON),
                    context,
                    connection,
                    new dev.openallay.agent.context.ContextBudget(contextWindow, maxOutput));
        }

        LocalAgentToolExecutor executor() {
            return executor;
        }

        AgentResult ask(
                GameGuideAgent agent,
                String question,
                String prompt,
                List<AgentEvent> events) throws Exception {
            UUID requestId = UUID.randomUUID();
            AgentRequest request = new AgentRequest(
                    requestId,
                    GroundedTestFixtures.PLAYER_ID,
                    "vfs-live",
                    question,
                    prompt,
                    context,
                    false);
            try (ResourceRequestRegistry.RequestHandle ignored = resources.open(
                    request.actorId(),
                    request.sessionId(),
                    request.requestId(),
                    connection,
                    "live-client",
                    Set.of(
                            "openallay:resource_list",
                            "openallay:resource_read",
                            "openallay:resource_glob",
                            "openallay:resource_grep",
                            "openallay:resource_query"),
                    contextBudget,
                    context)) {
                return agent.ask(request, events::add).get(8, TimeUnit.MINUTES);
            }
        }

        @Override
        public void close() {
            resources.close();
        }
    }

    private static ToolInvocationContext buildContext(String correlation) {
        ArrayList<RegistryEntrySnapshot> entries = new ArrayList<>();
        entries.add(item("minecraft:wooden_sword", "Wooden Sword", 4.0D, Set.of("minecraft:swords")));
        entries.add(item("minecraft:iron_sword", "Iron Sword", 6.0D, Set.of("minecraft:swords")));
        entries.add(item("minecraft:diamond_sword", "Diamond Sword", 7.0D, Set.of("minecraft:swords")));
        entries.add(item("minecraft:netherite_sword", "Netherite Sword", 8.0D, Set.of("minecraft:swords")));
        entries.add(item("minecraft:stick", "Stick", null, Set.of()));
        entries.add(item("minecraft:oak_planks", "Oak Planks", null, Set.of("minecraft:planks")));
        entries.add(item("minecraft:iron_ingot", "Iron Ingot", null, Set.of()));
        entries.add(item("minecraft:chest", "Chest", null, Set.of("minecraft:containers")));
        entries.add(item("minecraft:barrel", "Barrel", null, Set.of("minecraft:containers")));
        entries.add(item("minecraft:hopper", "Hopper", null, Set.of("minecraft:containers")));

        RegistrySnapshot registries = new RegistrySnapshot(
                GroundedTestFixtures.serverEvidence(), entries);

        String generation = GroundedTestFixtures.RECIPE_GENERATION;
        List<RecipeEntrySnapshot> recipes = List.of(
                recipe(
                        "minecraft:chest",
                        "Chest",
                        generation,
                        List.of(requirement("planks", 8, "minecraft:oak_planks")),
                        "minecraft:chest"),
                recipe(
                        "minecraft:barrel",
                        "Barrel",
                        generation,
                        List.of(
                                requirement("planks", 6, "minecraft:oak_planks"),
                                requirement("slabs", 2, "minecraft:oak_planks")),
                        "minecraft:barrel"),
                recipe(
                        "minecraft:hopper",
                        "Hopper",
                        generation,
                        List.of(
                                requirement("iron", 5, "minecraft:iron_ingot"),
                                requirement("chest", 1, "minecraft:chest")),
                        "minecraft:hopper"));

        return new ToolInvocationContext(
                correlation,
                Instant.parse("2026-07-20T00:00:00Z"),
                new CallerSnapshot(
                        CallerKind.PLAYER, GroundedTestFixtures.PLAYER_ID, "Builder", true),
                Optional.of(GroundedTestFixtures.player()),
                Optional.of(registries),
                Optional.of(new RecipeSnapshot(GroundedTestFixtures.serverEvidence(), recipes)),
                Optional.of(GroundedTestFixtures.observableGameState()),
                new ContextMetrics(10, 3, 3, 0, 0));
    }

    private static RegistryEntrySnapshot item(
            String id, String name, Double attackDamage, Set<String> tags) {
        Map<String, com.google.gson.JsonElement> properties = attackDamage == null
                ? Map.of()
                : Map.of("minecraft:attack_damage", new JsonPrimitive(attackDamage));
        return new RegistryEntrySnapshot(
                id,
                "item",
                name,
                id.substring(0, id.indexOf(':')),
                "minecraft:registry",
                List.of(name.toLowerCase(Locale.ROOT)),
                tags,
                Set.of(),
                properties);
    }

    private static IngredientRequirementSnapshot requirement(
            String key, long count, String itemId) {
        return new IngredientRequirementSnapshot(
                key,
                count,
                true,
                List.of(new IngredientAlternativeSnapshot(
                        "item", itemId, List.of(itemId))));
    }

    private static RecipeEntrySnapshot recipe(
            String recipeId,
            String outputName,
            String generation,
            List<IngredientRequirementSnapshot> inputs,
            String outputId) {
        return new RecipeEntrySnapshot(
                new RecipeReference("minecraft:recipe_manager", generation, recipeId),
                recipeId,
                "minecraft:crafting",
                new RecipeLayoutSnapshot(3, 3, true),
                "minecraft:crafting_table",
                inputs,
                List.of(),
                List.of(),
                List.of(new RecipeOutputSnapshot(
                        new ItemStackSnapshot(outputId, 1, outputName), 1.0D)),
                List.of(),
                RecipeProcessingSnapshot.unknown(),
                List.of(),
                Map.of(),
                GroundedTestFixtures.serverEvidence());
    }

    private static final class TestPlatform implements PlatformService {
        @Override public String platformName() { return "common-test"; }
        @Override public String gameVersion() { return "26.2"; }
        @Override public boolean isModLoaded(String modId) { return "farmersdelight".equals(modId); }
        @Override public boolean isDevelopmentEnvironment() { return true; }
        @Override public List<InstalledModMetadata> installedMods() {
            return List.of(new InstalledModMetadata(
                    "farmersdelight", "Farmer's Delight", "1.2.9", "fixture",
                    List.of(), List.of(), Map.of(), "client", List.of()));
        }
        @Override public ModResourceSnapshot captureModResources() {
            return ModResourceSnapshot.unavailable(Instant.parse("2026-07-20T00:00:00Z"), "fixture");
        }
    }
}
