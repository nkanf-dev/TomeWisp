package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.integration.patchouli.PatchouliMultiblockStore;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class GuideCommandFacadeTest {
    @Test
    void reportsCommonStructuredFailuresWithoutLoaderLogic() {
        ToolRegistry tools = new ToolRegistry();
        TomeWispRuntime runtime = new TomeWispRuntime(
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
                    java.util.function.Consumer<dev.tomewisp.agent.AgentEvent> events) { return false; }
            @Override public boolean cancel(UUID id) { return false; }
            @Override public void disconnect() {}
        };
        GuideContextProvider contexts = (capabilities, correlation) ->
                new ToolResult.Success<>(dev.tomewisp.context.ToolInvocationContext
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

    private static final class FakePlatform implements PlatformService {
        @Override public String platformName() { return "test"; }
        @Override public String gameVersion() { return "test"; }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public boolean isDevelopmentEnvironment() { return true; }
    }
}
