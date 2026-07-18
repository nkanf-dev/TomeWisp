package dev.tomewisp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.AgentResult;
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
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static ClientModelRuntimeRegistry registry(
            ModelProfilesConfigLoader.Load load,
            Map<String, ModelClient> clients) {
        return new ClientModelRuntimeRegistry(
                runtime(),
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
                new ModelProfilesConfig(1, defaultId, definitions),
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
}
