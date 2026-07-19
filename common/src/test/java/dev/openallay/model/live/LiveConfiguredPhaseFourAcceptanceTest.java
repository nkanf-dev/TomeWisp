package dev.openallay.model.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.openallay.agent.AgentEvent;
import dev.openallay.agent.AgentRequest;
import dev.openallay.agent.AgentResult;
import dev.openallay.agent.AgentSystemPrompt;
import dev.openallay.agent.GameGuideAgent;
import dev.openallay.agent.session.AgentSessionStore;
import dev.openallay.agent.tool.LocalAgentToolExecutor;
import dev.openallay.context.RegistryEntrySnapshot;
import dev.openallay.context.RegistrySnapshot;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.game.ObservableGameStateSnapshot;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.knowledge.online.McModKnowledgeSource;
import dev.openallay.knowledge.online.MinecraftWikiKnowledgeSource;
import dev.openallay.knowledge.online.OnlineKnowledgeSearchService;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ProviderModelClients;
import dev.openallay.model.catalog.ModelCatalog;
import dev.openallay.model.catalog.ModelCatalogRequest;
import dev.openallay.model.catalog.ProviderModelCatalogClient;
import dev.openallay.model.config.CredentialResolver;
import dev.openallay.model.config.LocalCredentialStore;
import dev.openallay.model.config.ModelConfig;
import dev.openallay.model.config.ModelProfilesConfigLoader;
import dev.openallay.model.config.ResolvedModelProfile;
import dev.openallay.model.scheduling.ModelRequestScheduler;
import dev.openallay.net.HttpTransportPolicy;
import dev.openallay.net.JdkHttpTransport;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.skill.BundledSkillLoader;
import dev.openallay.skill.LoadSkillTool;
import dev.openallay.skill.SkillParser;
import dev.openallay.skill.SkillRepository;
import dev.openallay.testing.GroundedTestFixtures;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.builtin.CalculateCraftabilityTool;
import dev.openallay.tool.builtin.FindItemUsagesTool;
import dev.openallay.tool.builtin.GetKnowledgeDocumentTool;
import dev.openallay.tool.builtin.GetPatchouliMultiblockTool;
import dev.openallay.tool.builtin.GetRecipeTool;
import dev.openallay.tool.builtin.InspectGameStateTool;
import dev.openallay.tool.builtin.InspectInventoryTool;
import dev.openallay.tool.builtin.ListKnowledgeSourcesTool;
import dev.openallay.tool.builtin.ResolveResourceTool;
import dev.openallay.tool.builtin.SearchKnowledgeTool;
import dev.openallay.tool.builtin.SearchRecipesTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in, billable multi-turn acceptance for the final Phase 4 provider path. */
final class LiveConfiguredPhaseFourAcceptanceTest {
    @Test
    void deepSeekCompletesSkillFirstBulkAndMultiTurnGameTasks() throws Exception {
        Map<String, String> environment = System.getenv();
        Assumptions.assumeTrue(Boolean.parseBoolean(
                environment.get("OPENALLAY_LIVE_PHASE_FOUR")));
        Path configPath = Path.of(required(environment, "OPENALLAY_LIVE_CONFIG_PATH"));
        Path credentialPath = Path.of(required(environment, "OPENALLAY_LIVE_CREDENTIAL_DB"));
        String selectedProfile = environment.getOrDefault("OPENALLAY_LIVE_PROFILE", "profile-1");
        String modelOverride = environment.getOrDefault(
                "OPENALLAY_LIVE_MODEL_OVERRIDE", "deepseek-v4-pro");
        String scenario = environment.getOrDefault("OPENALLAY_LIVE_SCENARIO", "all");
        boolean bulkOnly = scenario.equals("bulk");
        boolean simpleOnly = scenario.equals("simple");

        try (LocalCredentialStore credentials = new LocalCredentialStore(
                credentialPath, Clock.systemUTC())) {
            ModelProfilesConfigLoader.Load loaded = ((ToolResult.Success<ModelProfilesConfigLoader.Load>)
                    new ModelProfilesConfigLoader().load(
                            configPath,
                            configPath.resolveSibling("no-legacy-model.json"),
                            CredentialResolver.composite(credentials, environment),
                            environment,
                            Map.of())).value();
            ResolvedModelProfile profile = loaded.profiles().stream()
                    .filter(candidate -> candidate.definition().id().equals(selectedProfile))
                    .findFirst().orElseThrow();
            assertTrue(profile.available(), "configured live profile is unavailable");
            ModelCatalog catalog = ((ToolResult.Success<ModelCatalog>)
                    new ProviderModelCatalogClient(profile.definition().connectTimeout())
                            .fetch(new ModelCatalogRequest(
                                            profile.definition().id(),
                                            profile.definition().protocol(),
                                            profile.definition().baseUri(),
                                            profile.definition().credentialRef(),
                                            profile.definition().connectTimeout(),
                                            profile.definition().requestTimeout()),
                                    profile.runtimeConfig().apiKey(),
                                    new CancellationSignal())
                            .get(2, TimeUnit.MINUTES)).value();
            assertTrue(catalog.modelIds().contains(modelOverride),
                    "provider catalog does not contain requested acceptance model");

            ModelConfig base = profile.runtimeConfig();
            ModelConfig model = new ModelConfig(
                    true,
                    base.protocol(),
                    base.baseUri(),
                    modelOverride,
                    base.apiKey(),
                    base.contextWindowTokens(),
                    base.maxOutputTokens(),
                    base.connectTimeout(),
                    base.requestTimeout());
            Gson gson = new Gson();
            Runtime runtime = runtime(gson);
            GameGuideAgent agent = new GameGuideAgent(
                    new ModelRequestScheduler(ProviderModelClients.create(model, gson)),
                    runtime.executor(),
                    new AgentSessionStore(),
                    gson);
            String prompt = AgentSystemPrompt.compose(runtime.skills().metadataPrompt());
            UUID actor = UUID.randomUUID();
            ToolInvocationContext context = context();
            List<Run> runs = new ArrayList<>();
            Set<String> longSessionSkills = new HashSet<>();

            if (!bulkOnly && !simpleOnly) {
                runs.add(run(agent, actor, "casual", "嗨", prompt, context));
                assertEquals(List.of(), runs.getLast().tools());
            }

            if (!simpleOnly) {
                Run bulk = run(agent, actor, "bulk",
                        "农夫乐事里饱和度最高的三个食物是什么？请直接比较游戏数据，不要逐个查询。",
                        prompt, context);
                runs.add(bulk);
                assertSkillReady(bulk, "openallay:resolve_resource", "analyze-game-data", Set.of());
                assertEquals(2, count(bulk.tools(), "openallay:resolve_resource"),
                        () -> "expected one schema discovery plus one batch query; calls=" + bulk.calls());
                assertTrue(bulk.answer().contains("蜜汁火腿")
                                || bulk.answer().contains("honey_glazed_ham"),
                        bulk.answer());
                if (bulkOnly) {
                    writeReport(environment.get("OPENALLAY_LIVE_REPORT"), modelOverride, runs);
                    return;
                }
            }

            Run mods = run(agent, actor, "long",
                    "我安装了多少个模组？列出名称和版本。", prompt, context);
            runs.add(mods);
            assertDirectLookup(mods, "openallay:inspect_game_state");
            assertTrue(mods.answer().contains("3"), mods.answer());

            Run biome = run(agent, actor, "long",
                    "我现在在哪个生物群系，坐标和朝向是什么？", prompt, context);
            runs.add(biome);
            assertDirectLookup(biome, "openallay:inspect_game_state");
            assertTrue(biome.answer().contains("minecraft:plains")
                            || biome.answer().contains("平原"),
                    biome.answer());
            if (simpleOnly) {
                writeReport(environment.get("OPENALLAY_LIVE_REPORT"), modelOverride, runs);
                return;
            }

            Run followup = run(agent, actor, "long",
                    "回到食物问题：把饱和度前三名按从高到低列成简短表格。",
                    prompt, context);
            runs.add(followup);
            assertSkillReady(followup, "openallay:resolve_resource", "analyze-game-data", longSessionSkills);
            longSessionSkills.addAll(followup.loadedSkills());
            assertTrue(count(followup.tools(), "openallay:resolve_resource") <= 2,
                    () -> "follow-up repeated the batch query: " + followup.calls());

            Run knowledge = run(agent, actor, "long",
                    "Minecraft 的中毒效果能不能直接杀死玩家？当前游戏数据不够时请查公开知识来源。",
                    prompt, context);
            runs.add(knowledge);
            assertTrue(count(knowledge.tools(), "openallay:search_knowledge") >= 1);

            assertTrue(runs.stream().allMatch(Run::success));
            assertFalse(runs.stream().anyMatch(run -> run.failureCode() != null));
            writeReport(environment.get("OPENALLAY_LIVE_REPORT"), modelOverride, runs);
            System.out.println("OPENALLAY_LIVE_PHASE_FOUR code=success model=" + modelOverride
                    + " runs=" + runs.size() + " tools="
                    + runs.stream().mapToInt(run -> run.tools().size()).sum());
        }
    }

    private static Runtime runtime(Gson gson) {
        KnowledgeRegistry knowledge = new KnowledgeRegistry();
        JdkHttpTransport transport = new JdkHttpTransport(
                new HttpTransportPolicy(Duration.ofSeconds(10), "live-knowledge"));
        OnlineKnowledgeSearchService online = new OnlineKnowledgeSearchService(List.of(
                new MinecraftWikiKnowledgeSource(transport, gson),
                new McModKnowledgeSource(transport)));
        List<Tool<?, ?>> domainTools = List.of(
                new ResolveResourceTool(),
                new SearchRecipesTool(),
                new GetRecipeTool(),
                new FindItemUsagesTool(),
                new InspectInventoryTool(),
                new CalculateCraftabilityTool(),
                new InspectGameStateTool(),
                new ListKnowledgeSourcesTool(knowledge),
                new SearchKnowledgeTool(knowledge, online),
                new GetKnowledgeDocumentTool(knowledge),
                new GetPatchouliMultiblockTool(new PatchouliMultiblockStore()));
        ToolRegistry tools = new ToolRegistry();
        tools.register("live", domainTools);
        SkillRepository skills = new SkillRepository(
                new SkillParser(),
                tools.descriptors().stream().map(descriptor -> descriptor.id()).toList());
        assertTrue(skills.reload(new BundledSkillLoader().load(), Set.of()));
        tools.register("skills", List.of(new LoadSkillTool(skills)));
        return new Runtime(new LocalAgentToolExecutor(tools, gson), skills);
    }

    private static ToolInvocationContext context() {
        ToolInvocationContext base = GroundedTestFixtures.fullContext();
        List<RegistryEntrySnapshot> entries = List.of(
                food("farmersdelight:honey_glazed_ham", "蜜汁火腿", "14", "16"),
                food("farmersdelight:roast_chicken", "烤鸡", "12", "14.4"),
                food("farmersdelight:steak_and_potatoes", "牛排配马铃薯", "12", "12.8"),
                food("farmersdelight:grilled_salmon", "香烤鲑鱼", "9", "10.8"),
                food("farmersdelight:apple_cider", "苹果酒", "4", "4.8"),
                new RegistryEntrySnapshot(
                        "minecraft:poison", "effect", "中毒", "minecraft",
                        "minecraft:registry", List.of("poison"), Set.of(), Set.of(), Map.of()),
                new RegistryEntrySnapshot(
                        "minecraft:poisonous_potato", "item", "毒马铃薯", "minecraft",
                        "minecraft:registry", List.of(), Set.of(), Set.of(), Map.of()),
                new RegistryEntrySnapshot(
                        "minecraft:spider_eye", "item", "蜘蛛眼", "minecraft",
                        "minecraft:registry", List.of(), Set.of(), Set.of(), Map.of()));
        ObservableGameStateSnapshot observed = observable(base.observableGameState().orElseThrow());
        return new ToolInvocationContext(
                "live-phase-four",
                Instant.EPOCH,
                base.caller(),
                base.player(),
                java.util.Optional.of(new RegistrySnapshot(
                        base.registries().orElseThrow().evidence(), entries)),
                base.recipes(),
                java.util.Optional.of(observed),
                base.metrics());
    }

    private static RegistryEntrySnapshot food(
            String id, String displayName, String nutrition, String saturation) {
        JsonObject food = new JsonObject();
        food.addProperty("nutrition", Integer.parseInt(nutrition));
        food.addProperty("saturation", Double.parseDouble(saturation));
        return new RegistryEntrySnapshot(
                id,
                "item",
                displayName,
                "farmersdelight",
                "minecraft:registry",
                List.of(),
                Set.of("c:foods"),
                Set.of("minecraft:food"),
                Map.of("minecraft:food", food));
    }

    private static ObservableGameStateSnapshot observable(ObservableGameStateSnapshot base) {
        var evidence = base.runtime().evidence();
        return new ObservableGameStateSnapshot(
                base.capturedAt(),
                new ObservableGameStateSnapshot.RuntimeState(
                        "26.2", "Fabric", false, "singleplayer", evidence, List.of()),
                new ObservableGameStateSnapshot.ModsState(List.of(
                        mod("openallay", "OpenAllay", "0.1.0"),
                        mod("farmersdelight", "Farmer's Delight", "26.2-test"),
                        mod("fabric-api", "Fabric API", "test")), evidence, List.of()),
                base.options(),
                base.packs(),
                base.shaders(),
                new ObservableGameStateSnapshot.DiagnosticsState(List.of(
                        new ObservableGameStateSnapshot.DiagnosticValue(
                                "position", "coordinates", "128 72 -45"),
                        new ObservableGameStateSnapshot.DiagnosticValue(
                                "position", "biome", "minecraft:plains"),
                        new ObservableGameStateSnapshot.DiagnosticValue(
                                "position", "dimension", "minecraft:overworld"),
                        new ObservableGameStateSnapshot.DiagnosticValue(
                                "position", "direction", "south (yaw 3.2, pitch 12.0)")),
                        evidence, List.of()),
                base.player(),
                base.worldQueries());
    }

    private static InstalledModMetadata mod(String id, String name, String version) {
        return new InstalledModMetadata(
                id, name, version, "", List.of(), List.of(), Map.of(), "client", List.of());
    }

    private static Run run(
            GameGuideAgent agent,
            UUID actor,
            String session,
            String question,
            String prompt,
            ToolInvocationContext context) throws Exception {
        List<AgentEvent> events = new ArrayList<>();
        long started = System.nanoTime();
        AgentResult result = agent.ask(new AgentRequest(
                        UUID.randomUUID(), actor, session, question, prompt, context, true),
                events::add).get(8, TimeUnit.MINUTES);
        List<String> toolIds = events.stream()
                .filter(AgentEvent.ToolStarted.class::isInstance)
                .map(AgentEvent.ToolStarted.class::cast)
                .map(AgentEvent.ToolStarted::toolId)
                .toList();
        List<String> loadedSkills = events.stream()
                .filter(AgentEvent.ToolCompleted.class::isInstance)
                .map(AgentEvent.ToolCompleted.class::cast)
                .filter(event -> event.toolId().equals("openallay:load_skill") && !event.failure())
                .map(AgentEvent.ToolCompleted::normalized)
                .filter(normalized -> normalized.has("value")
                        && normalized.getAsJsonObject("value").has("name"))
                .map(normalized -> normalized.getAsJsonObject("value").get("name").getAsString())
                .toList();
        List<ToolCall> calls = events.stream()
                .filter(AgentEvent.ModelProgress.class::isInstance)
                .map(AgentEvent.ModelProgress.class::cast)
                .map(AgentEvent.ModelProgress::event)
                .filter(ModelEvent.ToolUseComplete.class::isInstance)
                .map(ModelEvent.ToolUseComplete.class::cast)
                .map(call -> new ToolCall(call.name(), call.input().toString()))
                .toList();
        Run run = new Run(
                session,
                result.successful(),
                result.text() == null ? "" : result.text(),
                result.errorCode(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                toolIds,
                loadedSkills,
                calls);
        assertTrue(run.success(), () -> "failure=" + run.failureCode() + " tools=" + toolIds);
        return run;
    }

    private static void assertSkillReady(
            Run run, String domainTool, String expectedSkill, Set<String> previouslyLoaded) {
        int skill = run.tools().indexOf("openallay:load_skill");
        int domain = run.tools().indexOf(domainTool);
        assertTrue(domain >= 0, () -> "Domain Tool not called: " + run.tools());
        if (previouslyLoaded.contains(expectedSkill)) {
            return;
        }
        assertTrue(run.loadedSkills().contains(expectedSkill),
                () -> "Expected Skill not loaded: expected=" + expectedSkill
                        + " actual=" + run.loadedSkills() + " tools=" + run.tools());
        assertTrue(skill >= 0 && domain > skill,
                () -> "Domain Tool ran before Skill: " + run.tools());
    }

    private static void assertDirectLookup(Run run, String domainTool) {
        assertTrue(run.tools().contains(domainTool), () -> "Domain Tool not called: " + run.tools());
        assertEquals(0, count(run.tools(), "openallay:load_skill"),
                () -> "Simple lookup loaded an unnecessary Skill: " + run.calls());
    }

    private static long count(List<String> tools, String id) {
        return tools.stream().filter(id::equals).count();
    }

    private static void writeReport(String target, String model, List<Run> runs) throws Exception {
        if (target == null || target.isBlank()) return;
        List<Map<String, Object>> reports = runs.stream().map(run -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("session", run.session());
            row.put("success", run.success());
            row.put("answerPresent", !run.answer().isBlank());
            row.put("failureCode", run.failureCode());
            row.put("elapsedMillis", run.elapsedMillis());
            row.put("tools", run.tools());
            row.put("loadedSkills", run.loadedSkills());
            row.put("calls", run.calls());
            return row;
        }).toList();
        Path path = Path.of(target);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "schemaVersion", 1,
                "model", model,
                "runs", reports)));
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        Assumptions.assumeTrue(value != null && !value.isBlank(), key + " is required");
        return value;
    }

    private record Runtime(LocalAgentToolExecutor executor, SkillRepository skills) {}
    private record Run(
            String session,
            boolean success,
            String answer,
            String failureCode,
            long elapsedMillis,
            List<String> tools,
            List<String> loadedSkills,
            List<ToolCall> calls) {}
    private record ToolCall(String tool, String input) {}
}
