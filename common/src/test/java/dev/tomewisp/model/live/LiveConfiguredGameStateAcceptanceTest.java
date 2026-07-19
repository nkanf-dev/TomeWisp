package dev.tomewisp.model.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentRequest;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.AgentSystemPrompt;
import dev.tomewisp.agent.GameGuideAgent;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.agent.tool.LocalAgentToolExecutor;
import dev.tomewisp.context.CallerKind;
import dev.tomewisp.context.CallerSnapshot;
import dev.tomewisp.context.ContextMetrics;
import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.context.game.ObservableGameStateSnapshot;
import dev.tomewisp.model.ProviderModelClients;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.catalog.ModelCatalog;
import dev.tomewisp.model.catalog.ModelCatalogRequest;
import dev.tomewisp.model.catalog.ProviderModelCatalogClient;
import dev.tomewisp.model.config.CredentialResolver;
import dev.tomewisp.model.config.LocalCredentialStore;
import dev.tomewisp.model.config.ModelProfilesConfigLoader;
import dev.tomewisp.model.config.ResolvedModelProfile;
import dev.tomewisp.model.scheduling.ModelRequestScheduler;
import dev.tomewisp.platform.InstalledModMetadata;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.tool.builtin.InspectGameStateTool;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Opt-in real-provider acceptance that resolves an ignored local credential internally. */
final class LiveConfiguredGameStateAcceptanceTest {
    @Test
    void realConfiguredModelKeepsGreetingsToolFreeAndAnswersFromTheGameStateTool()
            throws Exception {
        Map<String, String> environment = System.getenv();
        Assumptions.assumeTrue(Boolean.parseBoolean(
                environment.get("TOMEWISP_LIVE_CONFIGURED_AGENT")));
        Path config = Path.of(required(environment, "TOMEWISP_LIVE_CONFIG_PATH"));
        Path credentials = Path.of(required(environment, "TOMEWISP_LIVE_CREDENTIAL_DB"));

        try (LocalCredentialStore store = new LocalCredentialStore(
                credentials, Clock.systemUTC())) {
            ToolResult<ModelProfilesConfigLoader.Load> loaded =
                    new ModelProfilesConfigLoader().load(
                            config,
                            config.resolveSibling("no-legacy-model.json"),
                            CredentialResolver.composite(store, environment),
                            environment,
                            Map.of());
            assertTrue(loaded instanceof ToolResult.Success<ModelProfilesConfigLoader.Load>,
                    "configured live profile is invalid");
            ModelProfilesConfigLoader.Load profiles =
                    ((ToolResult.Success<ModelProfilesConfigLoader.Load>) loaded).value();
            String selected = environment.getOrDefault(
                    "TOMEWISP_LIVE_PROFILE", profiles.config().defaultProfileId());
            ResolvedModelProfile profile = profiles.profiles().stream()
                    .filter(candidate -> candidate.definition().id().equals(selected))
                    .findFirst().orElseThrow();
            assertTrue(profile.available(), "configured live profile is unavailable");

            ToolResult<ModelCatalog> catalogResult =
                    new ProviderModelCatalogClient(profile.definition().connectTimeout())
                            .fetch(
                                    new ModelCatalogRequest(
                                            profile.definition().id(),
                                            profile.definition().protocol(),
                                            profile.definition().baseUri(),
                                            profile.definition().credentialRef(),
                                            profile.definition().connectTimeout(),
                                            profile.definition().requestTimeout()),
                                    profile.runtimeConfig().apiKey(),
                                    new CancellationSignal())
                            .get(2, TimeUnit.MINUTES);
            ToolResult.Success<ModelCatalog> catalogSuccess = assertInstanceOf(
                    ToolResult.Success.class,
                    catalogResult,
                    () -> "provider catalog failed with "
                            + ((ToolResult.Failure<ModelCatalog>) catalogResult).code());
            ModelCatalog catalog = catalogSuccess.value();
            assertTrue(catalog.modelIds().contains(profile.definition().model()),
                    "provider catalog does not contain the configured model ID");

            Gson gson = new Gson();
            ToolRegistry registry = new ToolRegistry();
            registry.register("live-acceptance", List.of(new InspectGameStateTool()));
            LocalAgentToolExecutor tools = new LocalAgentToolExecutor(registry, gson);
            GameGuideAgent agent = new GameGuideAgent(
                    new ModelRequestScheduler(ProviderModelClients.create(
                            profile.runtimeConfig(), gson)),
                    tools,
                    new AgentSessionStore(),
                    gson);
            String prompt = AgentSystemPrompt.compose("") + """

                    For an installed-mod count, call the single matching game-state Tool
                    from the current Tool definitions before answering. For a greeting,
                    answer directly without a Tool.
                    """;

            List<AgentEvent> greetingEvents = new ArrayList<>();
            AgentResult greeting = agent.ask(request(
                            "greeting", "嗨", prompt, "live-greeting"),
                    greetingEvents::add).get(6, TimeUnit.MINUTES);
            assertTrue(greeting.successful(), greeting.errorMessage());
            assertEquals(0, toolStarts(greetingEvents));

            List<AgentEvent> factEvents = new ArrayList<>();
            AgentResult fact = agent.ask(request(
                            "mods", "我目前安装了多少个模组？", prompt, "live-mods"),
                    factEvents::add).get(6, TimeUnit.MINUTES);
            assertTrue(fact.successful(), fact.errorMessage());
            assertTrue(fact.text().contains("2"), fact.text() + " events=" + factEvents);
            assertTrue(factEvents.stream().anyMatch(event ->
                    event instanceof AgentEvent.ToolStarted started
                            && started.toolId().equals("tomewisp:inspect_game_state")));
            assertTrue(factEvents.stream().anyMatch(event ->
                    event instanceof AgentEvent.ToolCompleted completed
                            && completed.toolId().equals(
                                    "tomewisp:inspect_game_state")
                            && !completed.failure()));

            System.out.println("TOMEWISP_LIVE_CONFIGURED_AGENT code=success profile="
                    + selected + " catalogModels=" + catalog.modelIds().size()
                    + " greetingTools=0 gameStateTools=" + toolStarts(factEvents));
        }
    }

    private static AgentRequest request(
            String session, String question, String prompt, String correlation) {
        return new AgentRequest(
                UUID.randomUUID(), UUID.randomUUID(), session, question, prompt,
                context(correlation), true);
    }

    private static long toolStarts(List<AgentEvent> events) {
        return events.stream().filter(AgentEvent.ToolStarted.class::isInstance).count();
    }

    private static ToolInvocationContext context(String correlation) {
        return new ToolInvocationContext(
                correlation,
                Instant.EPOCH,
                new CallerSnapshot(CallerKind.CONSOLE, null, "Live acceptance", true),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(snapshot()),
                new ContextMetrics(0, 0, 0, 0, 0));
    }

    private static ObservableGameStateSnapshot snapshot() {
        EvidenceMetadata evidence = new EvidenceMetadata(
                DataAuthority.CLIENT_VISIBLE,
                DataCompleteness.COMPLETE,
                Instant.EPOCH,
                "tomewisp:observable_game_state",
                "tomewisp:live_acceptance_fixture",
                "26.2",
                "fabric",
                Map.of());
        return new ObservableGameStateSnapshot(
                Instant.EPOCH,
                new ObservableGameStateSnapshot.RuntimeState(
                        "26.2", "Fabric", false, "singleplayer", evidence, List.of()),
                new ObservableGameStateSnapshot.ModsState(List.of(
                        mod("farmersdelight", "Farmer's Delight"),
                        mod("tomewisp", "TomeWisp")), evidence, List.of()),
                new ObservableGameStateSnapshot.OptionsState(List.of(), evidence, List.of()),
                new ObservableGameStateSnapshot.PacksState(List.of(), List.of(), evidence, List.of()),
                new ObservableGameStateSnapshot.ShaderState(
                        false, "none", "", Map.of(), evidence, List.of()),
                new ObservableGameStateSnapshot.DiagnosticsState(List.of(), evidence, List.of()),
                new ObservableGameStateSnapshot.PlayerUiState(
                        null, "unavailable", "", evidence, List.of()),
                new ObservableGameStateSnapshot.WorldQueriesState(Map.of(), evidence, List.of()));
    }

    private static InstalledModMetadata mod(String id, String name) {
        return new InstalledModMetadata(
                id, name, "1", "", List.of(), List.of(), Map.of(), "client", List.of());
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }
}
