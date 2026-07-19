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
import dev.tomewisp.guide.history.GuideHistoryAccess;
import dev.tomewisp.guide.history.GuideHistoryLoad;
import dev.tomewisp.guide.history.GuideHistoryPartition;
import dev.tomewisp.guide.history.GuideHistoryScope;
import dev.tomewisp.model.ModelUsage;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.recipe.RecipeProviderReadiness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void waitsForDurableHistoryHydrationBeforeStarting() throws Exception {
        Path report = temporary.resolve("delayed/report.json");
        ArrayDeque<Runnable> clientTasks = new ArrayDeque<>();
        DelayedHistory history = new DelayedHistory();
        GuideClientE2EConfig config = new GuideClientE2EConfig(
                "fixture", "e2e", "question", GuideModelMode.CLIENT, report, true);
        GuideServiceManager services = new GuideServiceManager(
                new CompletingLocal(),
                new NoRemote(),
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                clientTasks::addLast,
                Clock.systemUTC(),
                new Gson(),
                history,
                actor -> GuideHistoryScope.derive(
                        actor, GuideHistoryScope.Kind.SINGLEPLAYER, "fixture-world"));
        AtomicBoolean shutdown = new AtomicBoolean();
        GuideClientE2EController controller = new GuideClientE2EController(
                config, "fabric", "26.2", "test", services, new Gson(),
                () -> shutdown.set(true), Set.of());
        UUID actor = UUID.fromString("30ab22ed-23fb-46f2-82ca-d4a656698eec");

        controller.tick(actor);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        assertFalse(controller.finished());
        assertFalse(Files.exists(report));

        history.loaded.complete(GuideHistoryLoad.empty());
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        controller.tick(actor);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        assertTrue(controller.finished());
        assertTrue(shutdown.get());
        assertEquals("COMPLETED", JsonParser.parseString(Files.readString(report))
                .getAsJsonObject().get("outcome").getAsString());
    }

    @Test
    void waitsForRecipeProvidersAndSubmitsOnlyOnceWhenReady() throws Exception {
        Path report = temporary.resolve("readiness/report.json");
        ArrayDeque<Runnable> clientTasks = new ArrayDeque<>();
        CountingLocal local = new CountingLocal();
        AtomicReference<RecipeProviderReadiness> readiness = new AtomicReference<>(
                RecipeProviderReadiness.waiting(
                        "recipe_provider_loading", "Viewer registry is loading"));
        GuideServiceManager services = services(local, clientTasks);
        GuideClientE2EController controller = new GuideClientE2EController(
                config(report), "fabric", "26.2", "test", services, new Gson(),
                () -> {}, Set.of(), readiness::get);
        UUID actor = UUID.fromString("30ab22ed-23fb-46f2-82ca-d4a656698eec");

        controller.tick(actor);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();
        assertFalse(controller.finished());
        assertEquals(0, local.calls.get());

        readiness.set(RecipeProviderReadiness.ready());
        controller.tick(actor);
        controller.tick(actor);
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        assertTrue(controller.finished());
        assertEquals(1, local.calls.get());
        assertEquals("COMPLETED", JsonParser.parseString(Files.readString(report))
                .getAsJsonObject().get("outcome").getAsString());
    }

    @Test
    void recipeProviderFailureWritesHarnessFailureWithoutSubmitting() throws Exception {
        Path report = temporary.resolve("readiness-failed/report.json");
        ArrayDeque<Runnable> clientTasks = new ArrayDeque<>();
        CountingLocal local = new CountingLocal();
        GuideClientE2EController controller = new GuideClientE2EController(
                config(report), "fabric", "26.2", "test", services(local, clientTasks), new Gson(),
                () -> {}, Set.of(), () -> RecipeProviderReadiness.failed(
                        "recipe_provider_failed", "JEI capture failed"));

        controller.tick(UUID.fromString("30ab22ed-23fb-46f2-82ca-d4a656698eec"));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        assertTrue(controller.finished());
        assertEquals(0, local.calls.get());
        var json = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        assertEquals("HARNESS_FAILED", json.get("outcome").getAsString());
        assertEquals("recipe_provider_failed", json.get("failureCode").getAsString());
    }

    @Test
    void seedsLongHistoryBeforeReportingTheAcceptanceRequest() throws Exception {
        Path report = temporary.resolve("history-seed/report.json");
        ArrayDeque<Runnable> clientTasks = new ArrayDeque<>();
        CountingLocal local = new CountingLocal();
        GuideClientE2EConfig config = new GuideClientE2EConfig(
                "fixture", "e2e", "question", GuideModelMode.CLIENT, report, true, 3);
        GuideClientE2EController controller = new GuideClientE2EController(
                config, "fabric", "26.2", "test", services(local, clientTasks), new Gson(),
                () -> {}, Set.of());

        controller.tick(UUID.fromString("30ab22ed-23fb-46f2-82ca-d4a656698eec"));
        while (!clientTasks.isEmpty()) clientTasks.removeFirst().run();

        assertTrue(controller.finished());
        assertEquals(4, local.calls.get());
        var json = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        assertEquals(4, json.getAsJsonObject("historyMetrics")
                .get("totalRequests").getAsLong());
        assertEquals("COMPLETED", json.get("outcome").getAsString());
    }

    private static GuideClientE2EConfig config(Path report) {
        return new GuideClientE2EConfig(
                "fixture", "e2e", "question", GuideModelMode.CLIENT, report, true);
    }

    private static GuideServiceManager services(
            GuideLocalEndpoint local, ArrayDeque<Runnable> clientTasks) {
        return new GuideServiceManager(
                local,
                new NoRemote(),
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                clientTasks::addLast,
                Clock.systemUTC(),
                new Gson());
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

    private static final class CountingLocal implements GuideLocalEndpoint {
        private final AtomicInteger calls = new AtomicInteger();
        private final CompletingLocal delegate = new CompletingLocal();

        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }

        @Override
        public CompletableFuture<AgentResult> ask(
                UUID actor,
                String sessionId,
                UUID requestId,
                String question,
                ToolInvocationContext context,
                Consumer<AgentEvent> events) {
            calls.incrementAndGet();
            return delegate.ask(actor, sessionId, requestId, question, context, events);
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

    private static final class DelayedHistory implements GuideHistoryAccess {
        private final CompletableFuture<GuideHistoryLoad> loaded = new CompletableFuture<>();

        @Override
        public CompletableFuture<GuideHistoryLoad> load(GuideHistoryScope scope) {
            return loaded;
        }

        @Override
        public CompletableFuture<Void> save(GuideHistoryPartition partition) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> delete(
                dev.tomewisp.guide.history.GuideHistoryDeleteScope scope) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Void> resetDatabase() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletableFuture<Void> flush() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public dev.tomewisp.guide.history.GuideHistoryActivity activity() {
            return dev.tomewisp.guide.history.GuideHistoryActivity.idle();
        }
    }
}
