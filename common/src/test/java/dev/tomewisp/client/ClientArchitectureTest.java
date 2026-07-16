package dev.tomewisp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.session.AgentSessionStore;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ClientArchitectureTest {
    @Test
    void modelCallbacksArePublishedThroughClientDispatcher() {
        List<Runnable> queued = new ArrayList<>();
        ToolRegistry tools = new ToolRegistry();
        TomeWispRuntime base = new TomeWispRuntime(
                new FakePlatform(),
                tools,
                new KnowledgeRegistry(),
                new SkillRepository(new SkillParser(), List.of()),
                new DevelopmentToolInspector(tools),
                null);
        ClientGuideRuntime runtime = new ClientGuideRuntime(
                base,
                (request, events, cancellation) -> java.util.concurrent.CompletableFuture.completedFuture(
                        new ModelTurn("test", "test", List.of(new ModelContent.Text("answer")),
                                "end_turn", ModelUsage.empty())),
                new AgentSessionStore(),
                new Gson(),
                queued::add);
        List<AgentEvent> delivered = new ArrayList<>();

        runtime.ask(UUID.randomUUID(), "question",
                dev.tomewisp.context.ToolInvocationContext.developmentConsole("test"), delivered::add).join();
        assertEquals(0, delivered.size());
        queued.forEach(Runnable::run);
        assertEquals(4, delivered.size());
    }

    private static final class FakePlatform implements PlatformService {
        @Override public String platformName() { return "test"; }
        @Override public boolean isModLoaded(String modId) { return false; }
        @Override public boolean isDevelopmentEnvironment() { return true; }
    }
}
