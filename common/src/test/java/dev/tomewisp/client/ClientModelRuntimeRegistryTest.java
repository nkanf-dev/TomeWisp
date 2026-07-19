package dev.tomewisp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.capability.CapabilityPolicy;
import dev.tomewisp.capability.ClientCapabilityResolver;
import dev.tomewisp.capability.ClientCapabilitySnapshot;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.guide.GuideModelProfileException;
import dev.tomewisp.integration.patchouli.PatchouliMultiblockStore;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ModelProfilesConfigLoader;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.ResolvedModelProfile;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ClientModelRuntimeRegistryTest {
    @Test
    void routesByProfileAndSharesProviderNeutralHistory() {
        RecordingModel modelA = new RecordingModel("model-a");
        RecordingModel modelB = new RecordingModel("model-b");
        ClientModelRuntimeRegistry registry = registry(
                load("a", "a", "b"), Map.of("a", modelA, "b", modelB));
        UUID actor = UUID.randomUUID();

        AgentResult first = registry.ask(
                "a", actor, "main", UUID.randomUUID(), "first question",
                dev.tomewisp.context.ToolInvocationContext.developmentConsole("test"), ignored -> {})
                .join();
        AgentResult second = registry.ask(
                "b", actor, "main", UUID.randomUUID(), "second question",
                dev.tomewisp.context.ToolInvocationContext.developmentConsole("test"), ignored -> {})
                .join();

        assertEquals("answer-model-a", first.text());
        assertEquals("answer-model-b", second.text());
        assertEquals(1, modelA.requests.size());
        assertEquals(1, modelB.requests.size());
        List<String> texts = modelB.requests.getFirst().messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ModelContent.Text.class::isInstance)
                .map(ModelContent.Text.class::cast)
                .map(ModelContent.Text::text)
                .toList();
        assertEquals(List.of("first question", "answer-model-a", "second question"), texts);
    }

    @Test
    void missingProfileFailsClosedAndNeverRoutesToDefault() {
        RecordingModel modelA = new RecordingModel("model-a");
        ClientModelRuntimeRegistry registry = registry(
                load("a", "a"), Map.of("a", modelA));

        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> registry.ask(
                        "missing", UUID.randomUUID(), "main", UUID.randomUUID(), "question",
                        dev.tomewisp.context.ToolInvocationContext.developmentConsole("test"),
                        ignored -> {}).join());

        GuideModelProfileException profileFailure = assertInstanceOf(
                GuideModelProfileException.class, failure.getCause());
        assertEquals("model_not_configured", profileFailure.code());
        assertTrue(modelA.requests.isEmpty());
    }

    @Test
    void atomicReplacementDoesNotCancelCapturedInFlightRuntime() {
        CompletableFuture<ModelTurn> pending = new CompletableFuture<>();
        RecordingModel modelA = new RecordingModel("model-a", pending);
        RecordingModel modelB = new RecordingModel("model-b");
        ClientModelRuntimeRegistry registry = registry(
                load("a", "a", "b"), Map.of("a", modelA, "b", modelB));

        CompletableFuture<AgentResult> inFlight = registry.ask(
                "a", UUID.randomUUID(), "main", UUID.randomUUID(), "question",
                dev.tomewisp.context.ToolInvocationContext.developmentConsole("test"), ignored -> {});
        registry.replace(load("b", "b"), profile -> modelB);
        pending.complete(turn("model-a"));

        assertEquals("answer-model-a", inFlight.join().text());
        assertEquals(List.of("b"), registry.profiles().stream().map(value -> value.id()).toList());
        assertEquals("b", registry.defaultProfileId());
    }

    @Test
    void preparedReplacementDoesNotPublishUntilExplicitOneTimeCommit() {
        RecordingModel modelA = new RecordingModel("model-a");
        RecordingModel modelB = new RecordingModel("model-b");
        ClientModelRuntimeRegistry registry = registry(
                load("a", "a"), Map.of("a", modelA, "b", modelB));

        ClientModelRuntimeRegistry.PreparedReplacement prepared =
                registry.prepare(load("b", "b"));

        assertEquals(List.of("a"), registry.profiles().stream()
                .map(value -> value.id()).toList());
        assertEquals("a", registry.defaultProfileId());

        prepared.publish();

        assertEquals(List.of("b"), registry.profiles().stream()
                .map(value -> value.id()).toList());
        assertEquals("b", registry.defaultProfileId());
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, prepared::publish);
    }

    @Test
    void capabilityReplacementAffectsFutureRequestsOnlyAndSharesEndpointAndHistory() {
        CompletableFuture<ModelTurn> pending = new CompletableFuture<>();
        ToolSequenceModel model = new ToolSequenceModel(pending);
        TomeWispRuntime product = runtimeWithFactTool();
        ClientModelRuntimeRegistry registry = registry(
                product, load("a", "a"), Map.of("a", model));
        UUID actor = UUID.randomUUID();
        Object endpointBefore = registry.endpointIdentity("a");

        CompletableFuture<AgentResult> active = registry.ask(
                "a", actor, "main", UUID.randomUUID(), "first question",
                ToolInvocationContext.developmentConsole("test"), ignored -> {});
        ClientCapabilitySnapshot withoutFact = success(new ClientCapabilityResolver().resolve(
                new CapabilityPolicy(1, Set.of("test:fact"), Set.of()),
                product.tools().registrations(),
                product.skills()));
        registry.replaceCapabilities(withoutFact);
        pending.complete(toolTurn("call-1", "test__fact", 42));

        assertTrue(active.join().successful());
        assertSame(endpointBefore, registry.endpointIdentity("a"));
        assertTrue(model.requests.getFirst().tools().stream()
                .anyMatch(tool -> tool.name().equals("test__fact")));
        assertTrue(model.requests.get(1).tools().stream()
                .anyMatch(tool -> tool.name().equals("test__fact")));

        AgentResult next = registry.ask(
                "a", actor, "main", UUID.randomUUID(), "second question",
                ToolInvocationContext.developmentConsole("test"), ignored -> {}).join();

        assertTrue(next.successful());
        ModelRequest nextRequest = model.requests.get(2);
        assertFalse(nextRequest.tools().stream()
                .anyMatch(tool -> tool.name().equals("test__fact")));
        List<String> text = nextRequest.messages().stream()
                .flatMap(message -> message.content().stream())
                .filter(ModelContent.Text.class::isInstance)
                .map(ModelContent.Text.class::cast)
                .map(ModelContent.Text::text)
                .toList();
        assertEquals(List.of("first question", "tool-complete", "second question"), text);
    }

    private static ClientModelRuntimeRegistry registry(
            ModelProfilesConfigLoader.Load load,
            Map<String, ModelClient> clients) {
        return registry(runtime(), load, clients);
    }

    private static ClientModelRuntimeRegistry registry(
            TomeWispRuntime runtime,
            ModelProfilesConfigLoader.Load load,
            Map<String, ModelClient> clients) {
        return new ClientModelRuntimeRegistry(
                runtime,
                load,
                new Gson(),
                Runnable::run,
                null,
                profile -> clients.get(profile.definition().id()));
    }

    private static ModelProfilesConfigLoader.Load load(
            String defaultId,
            String... ids) {
        List<ModelProfileDefinition> definitions = new ArrayList<>();
        List<ResolvedModelProfile> resolved = new ArrayList<>();
        for (String id : ids) {
            ModelProfileDefinition definition = new ModelProfileDefinition(
                    id,
                    "Profile " + id,
                    true,
                    ModelProtocol.OPENAI_CHAT,
                    URI.create("https://" + id + ".example/v1"),
                    "model-" + id,
                    "KEY_" + id.toUpperCase(),
                    128_000,
                    4096,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(300),
                    null);
            definitions.add(definition);
            resolved.add(new ResolvedModelProfile(
                    definition,
                    new ModelConfig(
                            true,
                            definition.protocol(),
                            definition.baseUri(),
                            definition.model(),
                            SecretValue.of("secret-" + id),
                            definition.contextWindowTokens(),
                            definition.maxOutputTokens(),
                            definition.connectTimeout(),
                            definition.requestTimeout()),
                    null));
        }
        return new ModelProfilesConfigLoader.Load(
                new ModelProfilesConfig(
                        ModelProfilesConfig.SCHEMA_VERSION, defaultId, definitions),
                resolved,
                false);
    }

    private static TomeWispRuntime runtime() {
        ToolRegistry tools = new ToolRegistry();
        return new TomeWispRuntime(
                new PlatformService() {
                    @Override public String platformName() { return "test"; }
                    @Override public boolean isModLoaded(String modId) { return false; }
                    @Override public boolean isDevelopmentEnvironment() { return true; }
                },
                tools,
                new KnowledgeRegistry(),
                new PatchouliMultiblockStore(),
                new SkillRepository(new SkillParser(), List.of()),
                new DevelopmentToolInspector(tools),
                null);
    }

    private static TomeWispRuntime runtimeWithFactTool() {
        ToolRegistry tools = new ToolRegistry();
        tools.register("test-provider", List.of(new Tool<FactInput, FactOutput>() {
            private final ToolDescriptor<FactInput, FactOutput> descriptor = new ToolDescriptor<>(
                    "test:fact",
                    "Return a fact",
                    FactInput.class,
                    FactOutput.class,
                    ToolAccess.READ_ONLY,
                    Set.of(ContextCapability.RECIPES));

            @Override public ToolDescriptor<FactInput, FactOutput> descriptor() { return descriptor; }
            @Override
            public ToolResult<FactOutput> invoke(ToolInvocationContext context, FactInput input) {
                return new ToolResult.Success<>(new FactOutput(input.value()));
            }
        }));
        return new TomeWispRuntime(
                new PlatformService() {
                    @Override public String platformName() { return "test"; }
                    @Override public boolean isModLoaded(String modId) { return false; }
                    @Override public boolean isDevelopmentEnvironment() { return true; }
                },
                tools,
                new KnowledgeRegistry(),
                new PatchouliMultiblockStore(),
                new SkillRepository(new SkillParser(), List.of("test:fact")),
                new DevelopmentToolInspector(tools),
                null);
    }

    private record FactInput(int value) {}
    private record FactOutput(int value) {}

    private static ModelTurn toolTurn(String id, String name, int value) {
        com.google.gson.JsonObject input = new com.google.gson.JsonObject();
        input.addProperty("value", value);
        return new ModelTurn(
                "test",
                "model-a",
                List.of(new ModelContent.ToolUse(id, name, input)),
                "tool_use",
                ModelUsage.empty());
    }

    private static ClientCapabilitySnapshot success(ToolResult<ClientCapabilitySnapshot> result) {
        return ((ToolResult.Success<ClientCapabilitySnapshot>) assertInstanceOf(
                ToolResult.Success.class, result)).value();
    }

    private static ModelTurn turn(String model) {
        return new ModelTurn(
                "test",
                model,
                List.of(new ModelContent.Text("answer-" + model)),
                "end_turn",
                ModelUsage.empty());
    }

    private static final class RecordingModel implements ModelClient {
        private final String model;
        private final CompletableFuture<ModelTurn> fixed;
        private final List<ModelRequest> requests = new ArrayList<>();

        private RecordingModel(String model) {
            this(model, null);
        }

        private RecordingModel(String model, CompletableFuture<ModelTurn> fixed) {
            this.model = model;
            this.fixed = fixed;
        }

        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request,
                Consumer<ModelEvent> events,
                CancellationSignal cancellation) {
            requests.add(request);
            return fixed == null ? CompletableFuture.completedFuture(turn(model)) : fixed;
        }
    }

    private static final class ToolSequenceModel implements ModelClient {
        private final CompletableFuture<ModelTurn> first;
        private final List<ModelRequest> requests = new ArrayList<>();

        private ToolSequenceModel(CompletableFuture<ModelTurn> first) {
            this.first = first;
        }

        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request,
                Consumer<ModelEvent> events,
                CancellationSignal cancellation) {
            requests.add(request);
            if (requests.size() == 1) {
                return first;
            }
            return CompletableFuture.completedFuture(new ModelTurn(
                    "test",
                    "model-a",
                    List.of(new ModelContent.Text(requests.size() == 2
                            ? "tool-complete"
                            : "next-complete")),
                    "end_turn",
                    ModelUsage.empty()));
        }
    }
}
