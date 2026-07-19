package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.AgentState;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class GuideServiceTest {
    private static final UUID ACTOR = UUID.fromString("30ab22ed-23fb-46f2-82ca-d4a656698eec");

    @Test
    void localRequestsPublishSnapshotsAndIsolateSessionConcurrency() {
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, new FakeRemote(false));
        List<GuideSnapshot> observed = new ArrayList<>();
        GuideSubscription subscription = service.subscribe(observed::add);

        UUID first = success(service.ask("first").join());
        ToolResult.Failure<UUID> busy = failure(service.ask("busy").join());
        assertEquals("agent_busy", busy.code());

        service.selectSession("other").join();
        UUID second = success(service.ask("second").join());
        assertEquals(2, local.pending.size());
        assertNotEquals(first, second);

        local.complete(first, "first answer");
        local.complete(second, "second answer");
        assertEquals(GuideRequestStatus.COMPLETED, request(service, "main", first).status());
        assertEquals(GuideRequestStatus.COMPLETED, request(service, "other", second).status());
        assertFalse(observed.isEmpty());

        int before = observed.size();
        subscription.close();
        service.selectSession("main").join();
        assertEquals(before, observed.size());
    }

    @Test
    void cancelSuppressesLateEventsAndRetryUsesNewIdentity() {
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, new FakeRemote(false));
        UUID request = success(service.ask("retry me").join());

        assertTrue(success(service.cancel().join()));
        assertEquals(GuideRequestStatus.CANCELLED, request(service, "main", request).status());
        local.complete(request, "late");
        assertEquals("", request(service, "main", request).assistantText());

        UUID retried = success(service.retry(request).join());
        assertNotEquals(request, retried);
        assertEquals("retry me", request(service, "main", retried).userMessage());
    }

    @Test
    void servicePreservesInterleavedAssistantAndToolTimeline() {
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, new FakeRemote(false));
        UUID requestId = success(service.ask("interleave").join());

        local.completeInterleaved(requestId);

        GuideRequestSnapshot request = request(service, "main", requestId);
        assertEquals(
                List.of(
                        GuideTimelineEntry.Assistant.class,
                        GuideTimelineEntry.Tool.class,
                        GuideTimelineEntry.Assistant.class,
                        GuideTimelineEntry.Tool.class,
                        GuideTimelineEntry.Assistant.class),
                request.timeline().stream().map(Object::getClass).toList());
        assertEquals("final answer", request.assistantText());
        assertEquals("final answer",
                ((GuideTimelineEntry.Assistant) request.timeline().getLast())
                        .semantic().fallbackText());
        assertEquals("final answer", service.snapshot().sessions().getFirst().messages().getLast().text());
    }

    @Test
    void serverModeWorksWithoutClientModelAndNeverFallsBack() {
        FakeRemote remote = new FakeRemote(true);
        GuideService service = service(null, remote);

        ToolResult.Failure<UUID> unconfigured = failure(service.ask("client").join());
        assertEquals("model_not_configured", unconfigured.code());
        assertInstanceOf(ToolResult.Success.class, service.setModelMode(GuideModelMode.SERVER).join());
        UUID request = success(service.ask("server").join());
        assertEquals(GuideTopology.SERVER, request(service, "main", request).topology());

        remote.fail(request, "server_model_failure", "no fallback");
        assertEquals(GuideRequestStatus.FAILED, request(service, "main", request).status());
        assertEquals(GuideModelMode.SERVER, service.snapshot().modelMode());
    }

    @Test
    void enhancedLocalTopologyAndCapabilityLossRemainExplicit() {
        FakeRemote remote = new FakeRemote(true);
        GuideService localService = service(new FakeLocal(), remote);
        UUID local = success(localService.ask("enhanced").join());
        assertEquals(
                GuideTopology.CLIENT_WITH_SERVER_TOOLS,
                request(localService, "main", local).topology());

        GuideService serverService = service(null, remote);
        success(serverService.setModelMode(GuideModelMode.SERVER).join());
        UUID active = success(serverService.ask("server").join());
        remote.available = false;
        serverService.refreshCapabilities().join();

        assertEquals(GuideRequestStatus.FAILED, request(serverService, "main", active).status());
        assertEquals(GuideModelMode.SERVER, serverService.snapshot().modelMode());
    }

    @Test
    void disconnectClearsConnectionScopedStateAndResetsMode() {
        FakeRemote remote = new FakeRemote(true);
        GuideService service = service(new FakeLocal(), remote);
        service.setModelMode(GuideModelMode.SERVER).join();
        success(service.ask("active").join());
        service.selectSession("other").join();

        service.disconnect().join();

        assertEquals(GuideModelMode.CLIENT, service.snapshot().modelMode());
        assertEquals("main", service.snapshot().selectedSession());
        assertEquals(List.of("main"), service.snapshot().sessions().stream()
                .map(GuideSessionSnapshot::sessionId).toList());
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());
        assertEquals(1, remote.cancelled.size());
    }

    @Test
    void capturesTheSelectedSessionForPlayerInitiatedExport() {
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, new FakeRemote(false));
        UUID request = success(service.ask("export this").join());
        local.complete(request, "exported answer");

        dev.tomewisp.guide.export.GuideSessionExportSnapshot exported = success(
                service.captureSelectedSessionForExport().join());

        assertEquals("main", exported.sessionId());
        assertEquals(Instant.EPOCH, exported.capturedAt());
        assertEquals(List.of("export this"), exported.requests().stream()
                .map(dev.tomewisp.guide.export.GuideSessionExportSnapshot.Request::userMessage)
                .toList());
        assertEquals("exported answer",
                ((dev.tomewisp.guide.export.GuideSessionExportSnapshot.Entry.Assistant)
                        exported.requests().getFirst().timeline().getFirst()).text());
    }

    @Test
    void confirmedSessionCloseCancelsItsActiveRequestAndFencesLateOutput() {
        FakeLocal local = new FakeLocal();
        GuideService service = service(local, new FakeRemote(false));
        UUID request = success(service.ask("active session").join());

        assertTrue(success(service.closeSession("main").join()));
        assertTrue(local.cancelled.contains("main"));
        assertEquals(List.of("main"), service.snapshot().sessions().stream()
                .map(GuideSessionSnapshot::sessionId).toList());
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());

        local.complete(request, "late answer");
        assertTrue(service.snapshot().sessions().getFirst().requests().isEmpty());
    }

    private static GuideService service(FakeLocal local, FakeRemote remote) {
        return new GuideService(
                ACTOR,
                local,
                remote,
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                Runnable::run,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                new Gson());
    }

    private static GuideRequestSnapshot request(
            GuideService service, String session, UUID request) {
        return service.snapshot().sessions().stream()
                .filter(value -> value.sessionId().equals(session))
                .flatMap(value -> value.requests().stream())
                .filter(value -> value.requestId().equals(request))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> T success(ToolResult<T> result) {
        return ((ToolResult.Success<T>) assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    @SuppressWarnings("unchecked")
    private static <T> ToolResult.Failure<T> failure(ToolResult<T> result) {
        return (ToolResult.Failure<T>) assertInstanceOf(ToolResult.Failure.class, result);
    }

    private static final class FakeLocal implements GuideLocalEndpoint {
        private final Map<UUID, Consumer<AgentEvent>> pending = new java.util.HashMap<>();
        private final Set<String> cancelled = new java.util.HashSet<>();

        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }

        @Override
        public CompletableFuture<AgentResult> ask(
                UUID actor,
                String sessionId,
                UUID requestId,
                String question,
                ToolInvocationContext context,
                Consumer<AgentEvent> events) {
            pending.put(requestId, events);
            events.accept(new AgentEvent.StateChanged(AgentState.MODEL_WAIT));
            return new CompletableFuture<>();
        }

        @Override public boolean cancel(UUID actor, String sessionId) {
            cancelled.add(sessionId);
            return true;
        }
        @Override public void clearSession(UUID actor, String sessionId) {}
        @Override public void clearActor(UUID actor) {}

        void complete(UUID request, String text) {
            Consumer<AgentEvent> events = pending.get(request);
            events.accept(new AgentEvent.StateChanged(AgentState.COMPLETED));
            events.accept(new AgentEvent.FinalText(text));
        }

        void completeInterleaved(UUID request) {
            Consumer<AgentEvent> events = pending.get(request);
            events.accept(new AgentEvent.ModelProgress(new dev.tomewisp.model.ModelEvent.TextDelta(
                    "I will check.")));
            events.accept(new AgentEvent.ToolStarted("call-1", "tomewisp:get_recipe"));
            events.accept(new AgentEvent.ToolCompleted(
                    "call-1", "tomewisp:get_recipe", false, new com.google.gson.JsonObject()));
            events.accept(new AgentEvent.ModelProgress(new dev.tomewisp.model.ModelEvent.TextDelta(
                    "Now inventory.")));
            events.accept(new AgentEvent.ToolStarted("call-2", "tomewisp:inspect_inventory"));
            events.accept(new AgentEvent.ToolCompleted(
                    "call-2", "tomewisp:inspect_inventory", false, new com.google.gson.JsonObject()));
            events.accept(new AgentEvent.FinalText("final answer"));
        }
    }

    private static final class FakeRemote implements GuideRemoteEndpoint {
        private boolean available;
        private final Map<UUID, Consumer<AgentEvent>> pending = new java.util.HashMap<>();
        private final List<UUID> cancelled = new ArrayList<>();

        private FakeRemote(boolean available) { this.available = available; }
        @Override public boolean serverModelAvailable() { return available; }
        @Override public boolean serverToolsAvailable() { return available; }
        @Override
        public boolean ask(
                UUID requestId, String sessionId, String question, Consumer<AgentEvent> events) {
            if (!available) return false;
            pending.put(requestId, events);
            events.accept(new AgentEvent.StateChanged(AgentState.MODEL_WAIT));
            return true;
        }
        @Override public boolean cancel(UUID requestId) {
            cancelled.add(requestId);
            return pending.remove(requestId) != null;
        }
        @Override public void disconnect() { pending.clear(); }

        void fail(UUID request, String code, String message) {
            pending.get(request).accept(new AgentEvent.Failed(code, message));
        }
    }
}
