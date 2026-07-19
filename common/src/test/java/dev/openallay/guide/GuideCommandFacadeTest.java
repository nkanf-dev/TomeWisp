package dev.openallay.guide;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import dev.openallay.OpenAllayRuntime;
import dev.openallay.devmode.DevelopmentToolInspector;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.platform.PlatformService;
import dev.openallay.skill.SkillParser;
import dev.openallay.skill.SkillRepository;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class GuideCommandFacadeTest {
    @Test
    void reportsCommonStructuredFailuresWithoutLoaderLogic() {
        ToolRegistry tools = new ToolRegistry();
        OpenAllayRuntime runtime = new OpenAllayRuntime(
                new FakePlatform(),
                tools,
                new KnowledgeRegistry(),
                new PatchouliMultiblockStore(),
                new SkillRepository(new SkillParser(), List.of()),
                new DevelopmentToolInspector(tools),
                null);
        GuideRemoteEndpoint remote = new GuideRemoteEndpoint() {
            @Override public boolean serverModelAvailable() { return false; }
            @Override public boolean serverToolsAvailable() { return false; }
            @Override public boolean ask(UUID id, String session, String question,
                    java.util.function.Consumer<dev.openallay.agent.AgentEvent> events) { return false; }
            @Override public boolean cancel(UUID id) { return false; }
            @Override public void disconnect() {}
        };
        GuideContextProvider contexts = (capabilities, correlation) ->
                new ToolResult.Success<>(dev.openallay.context.ToolInvocationContext
                        .developmentConsole(correlation));
        GuideServiceManager services = new GuideServiceManager(
                null, remote, contexts, Runnable::run, Clock.systemUTC(), new Gson());
        GuideCommandFacade commands = new GuideCommandFacade(
                runtime,
                services,
                contexts,
                service -> new ToolResult.Failure<>("gui_unavailable", "not built"));
        List<GuideNotice> notices = new ArrayList<>();
        UUID actor = UUID.randomUUID();

        commands.open(actor, notices::add);
        commands.ask(actor, "question", notices::add);
        commands.model(actor, GuideModelMode.SERVER, notices::add);

        assertTrue(notices.stream().anyMatch(value -> value.message().contains("gui_unavailable")));
        assertTrue(notices.stream().anyMatch(value -> value.message().contains("model_not_configured")));
        assertTrue(notices.stream().anyMatch(value -> value.message().contains("capability_unavailable")));
    }

    @Test
    void explicitProfileCommandChangesOnlyTheSelectedSessionsFutureModel() {
        ToolRegistry tools = new ToolRegistry();
        OpenAllayRuntime runtime = new OpenAllayRuntime(
                new FakePlatform(),
                tools,
                new KnowledgeRegistry(),
                new PatchouliMultiblockStore(),
                new SkillRepository(new SkillParser(), List.of()),
                new DevelopmentToolInspector(tools),
                null);
        GuideLocalEndpoint local = new GuideLocalEndpoint() {
            @Override public String defaultProfileId() { return "a"; }
            @Override public List<GuideClientModelProfile> profiles() {
                return List.of(
                        new GuideClientModelProfile("a", "Model A", true, true, "a", null),
                        new GuideClientModelProfile("b", "Model B", true, true, "b", null));
            }
            @Override public Set<dev.openallay.context.ContextCapability> requiredContext() {
                return Set.of();
            }
            @Override public CompletableFuture<dev.openallay.agent.AgentResult> ask(
                    UUID actor, String sessionId, UUID requestId, String question,
                    dev.openallay.context.ToolInvocationContext context,
                    java.util.function.Consumer<dev.openallay.agent.AgentEvent> events) {
                return new CompletableFuture<>();
            }
            @Override public boolean cancel(UUID actor, String sessionId) { return true; }
            @Override public void clearSession(UUID actor, String sessionId) {}
            @Override public void clearActor(UUID actor) {}
        };
        GuideRemoteEndpoint remote = new GuideRemoteEndpoint() {
            @Override public boolean serverModelAvailable() { return false; }
            @Override public boolean serverToolsAvailable() { return false; }
            @Override public boolean ask(UUID id, String session, String question,
                    java.util.function.Consumer<dev.openallay.agent.AgentEvent> events) { return false; }
            @Override public boolean cancel(UUID id) { return false; }
            @Override public void disconnect() {}
        };
        GuideContextProvider contexts = (capabilities, correlation) ->
                new ToolResult.Success<>(dev.openallay.context.ToolInvocationContext
                        .developmentConsole(correlation));
        GuideServiceManager services = new GuideServiceManager(
                local, remote, contexts, Runnable::run, Clock.systemUTC(), new Gson());
        GuideCommandFacade commands = new GuideCommandFacade(
                runtime, services, contexts, service -> new ToolResult.Success<>(true));
        UUID actor = UUID.randomUUID();
        services.forActor(actor).selectSession("other").join();
        List<GuideNotice> notices = new ArrayList<>();

        commands.modelProfile(actor, "b", notices::add);

        assertEquals(GuideModelSelection.client("b"), services.forActor(actor)
                .snapshot().modelSelection());
        services.forActor(actor).selectSession("main").join();
        assertEquals(GuideModelSelection.client("a"), services.forActor(actor)
                .snapshot().modelSelection());
        assertTrue(notices.stream().anyMatch(value -> value.message().contains("Model B")));

        commands.modelProfile(actor, "missing", notices::add);
        assertEquals(GuideModelSelection.client("a"), services.forActor(actor)
                .snapshot().modelSelection());
        assertTrue(notices.stream().anyMatch(value -> value.message()
                .contains("model_not_configured")));
    }

    private static final class FakePlatform implements PlatformService {
        @Override public String platformName() { return "test"; }
        @Override public String gameVersion() { return "test"; }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public boolean isDevelopmentEnvironment() { return true; }
    }
}
