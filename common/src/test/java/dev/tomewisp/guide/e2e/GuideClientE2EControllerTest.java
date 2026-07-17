package dev.tomewisp.guide.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.AgentState;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.guide.GuideLocalEndpoint;
import dev.tomewisp.guide.GuideModelMode;
import dev.tomewisp.guide.GuideRemoteEndpoint;
import dev.tomewisp.guide.GuideServiceManager;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GuideClientE2EControllerTest {
    @TempDir Path temporary;

    @Test
    void writesCanonicalReportAndRequestsCleanShutdown() throws Exception {
        Path report = temporary.resolve("nested/report.json");
        ArrayDeque<Runnable> clientTasks = new ArrayDeque<>();
        GuideClientE2EConfig config = new GuideClientE2EConfig(
                "fixture", "e2e", "question", GuideModelMode.CLIENT, report, true);
        GuideServiceManager services = new GuideServiceManager(
                new CompletingLocal(),
                new NoRemote(),
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                clientTasks::addLast,
                Clock.systemUTC(),
                new Gson());
        AtomicBoolean shutdown = new AtomicBoolean();
        GuideClientE2EController controller = new GuideClientE2EController(
                config, "fabric", "26.2", "test", services, new Gson(),
                () -> shutdown.set(true), Set.of("do-not-leak"));

        assertFalse(controller.finished());
        controller.tick(UUID.fromString("30ab22ed-23fb-46f2-82ca-d4a656698eec"));
        assertFalse(controller.finished());
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        assertTrue(controller.finished());
        assertTrue(shutdown.get());
        String encoded = Files.readString(report);
        assertFalse(encoded.contains("do-not-leak"));
        var json = JsonParser.parseString(encoded).getAsJsonObject();
        assertEquals("fabric", json.get("loader").getAsString());
        assertEquals("COMPLETED", json.get("outcome").getAsString());
        assertEquals("e2e", json.get("sessionId").getAsString());
    }

    private static final class CompletingLocal implements GuideLocalEndpoint {
        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }

        @Override
        public CompletableFuture<AgentResult> ask(
                UUID actor,
                String sessionId,
                UUID requestId,
                String question,
                ToolInvocationContext context,
                Consumer<AgentEvent> events) {
            events.accept(new AgentEvent.StateChanged(AgentState.MODEL_WAIT));
            events.accept(new AgentEvent.ModelProgress(new ModelEvent.TextDelta("done")));
            events.accept(new AgentEvent.ModelProgress(
                    new ModelEvent.UsageUpdate(new ModelUsage(2, 1, 0))));
            events.accept(new AgentEvent.StateChanged(AgentState.COMPLETED));
            events.accept(new AgentEvent.FinalText("done"));
            return CompletableFuture.completedFuture(
                    new AgentResult(AgentState.COMPLETED, "done", null, null, null));
        }

        @Override public boolean cancel(UUID actor, String sessionId) { return true; }
        @Override public void clearSession(UUID actor, String sessionId) {}
        @Override public void clearActor(UUID actor) {}
    }

    private static final class NoRemote implements GuideRemoteEndpoint {
        @Override public boolean serverModelAvailable() { return false; }
        @Override public boolean serverToolsAvailable() { return false; }
        @Override public boolean ask(UUID requestId, String sessionId, String question,
                Consumer<AgentEvent> events) { return false; }
        @Override public boolean cancel(UUID requestId) { return false; }
        @Override public void disconnect() {}
    }
}
