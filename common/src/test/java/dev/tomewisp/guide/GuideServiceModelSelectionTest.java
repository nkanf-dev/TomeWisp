package dev.tomewisp.guide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.agent.AgentEvent;
import dev.tomewisp.agent.AgentResult;
import dev.tomewisp.agent.AgentState;
import dev.tomewisp.agent.context.ContextCheckpoint;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class GuideServiceModelSelectionTest {
    private static final UUID ACTOR = UUID.fromString("30ab22ed-23fb-46f2-82ca-d4a656698eec");

    @Test
    void sessionsSelectDifferentProfilesAndRequestsCaptureThem() {
        ProfileLocal local = new ProfileLocal();
        GuideService service = service(local, false);

        UUID first = success(service.ask("first").join());
        service.selectSession("other").join();
        success(service.setModelSelection(GuideModelSelection.client("b")).join());
        UUID second = success(service.ask("second").join());

        assertEquals("a", local.profileByRequest.get(first));
        assertEquals("b", local.profileByRequest.get(second));
        assertEquals(GuideModelSelection.client("a"), request(service, "main", first).modelSelection());
        assertEquals(GuideModelSelection.client("b"), request(service, "other", second).modelSelection());
        assertEquals(GuideModelSelection.client("a"), session(service, "main").modelSelection());
        assertEquals(GuideModelSelection.client("b"), session(service, "other").modelSelection());
    }

    @Test
    void switchingWhileActiveChangesNextRequestButNotCapturedRuntimeOrHistory() {
        ProfileLocal local = new ProfileLocal();
        GuideService service = service(local, false);
        UUID active = success(service.ask("first").join());
        ContextCheckpoint checkpoint = new ContextCheckpoint(
                UUID.randomUUID(),
                0,
                1,
                "0".repeat(64),
                "model-a",
                1,
                1,
                Instant.EPOCH,
                ContextCheckpoint.Status.SUCCEEDED,
                "summary",
                null,
                null,
                10);
        local.checkpoint(active, checkpoint);

        success(service.setModelSelection(GuideModelSelection.client("b")).join());
        assertEquals(GuideModelSelection.client("a"), request(service, "main", active).modelSelection());
        assertEquals(GuideModelSelection.client("b"), session(service, "main").modelSelection());
        local.complete(active, "answer");
        UUID next = success(service.ask("second").join());

        assertEquals("a", local.profileByRequest.get(active));
        assertEquals("b", local.profileByRequest.get(next));
        assertEquals(List.of("first", "answer", "second"), session(service, "main").messages().stream()
                .map(GuideMessage::text).toList());
        assertEquals(List.of(checkpoint), session(service, "main").checkpoints());
    }

    @Test
    void retryUsesCurrentSelectionAndMissingOrInvalidProfilesNeverDispatch() {
        ProfileLocal local = new ProfileLocal();
        GuideService service = service(local, false);
        UUID failed = success(service.ask("retry").join());
        local.fail(failed, "provider_error", "failed");
        success(service.setModelSelection(GuideModelSelection.client("b")).join());

        UUID retried = success(service.retry(failed).join());
        assertEquals("b", local.profileByRequest.get(retried));

        int dispatches = local.profileByRequest.size();
        ToolResult.Failure<GuideModelSelection> missing = failure(
                service.setModelSelection(GuideModelSelection.client("missing")).join());
        assertEquals("model_not_configured", missing.code());
        ToolResult.Failure<GuideModelSelection> invalid = failure(
                service.setModelSelection(GuideModelSelection.client("broken")).join());
        assertEquals("invalid_model_config", invalid.code());
        assertEquals(dispatches, local.profileByRequest.size());
    }

    @Test
    void serverSelectionIsPerSessionAndCompatibilityModeRestoresLastClientProfile() {
        ProfileLocal local = new ProfileLocal();
        GuideService service = service(local, true);
        success(service.setModelSelection(GuideModelSelection.client("b")).join());
        success(service.setModelMode(GuideModelMode.SERVER).join());
        assertEquals(GuideModelSelection.server(), session(service, "main").modelSelection());
        assertEquals(GuideModelMode.SERVER, service.snapshot().modelMode());

        success(service.setModelMode(GuideModelMode.CLIENT).join());
        assertEquals(GuideModelSelection.client("b"), session(service, "main").modelSelection());
        assertEquals(GuideModelMode.CLIENT, service.snapshot().modelMode());

        service.selectSession("other").join();
        assertEquals(GuideModelSelection.client("a"), session(service, "other").modelSelection());
    }

    @Test
    void removedRememberedProfileStaysSelectedAndFailsFutureSubmission() {
        ProfileLocal local = new ProfileLocal();
        GuideService service = service(local, false);
        success(service.setModelSelection(GuideModelSelection.client("b")).join());
        local.remove("b");

        ToolResult.Failure<UUID> failure = failure(service.ask("cannot dispatch").join());

        assertEquals("model_not_configured", failure.code());
        assertEquals(GuideModelSelection.client("b"), session(service, "main").modelSelection());
        assertTrue(local.profileByRequest.isEmpty());
    }

    private static GuideService service(ProfileLocal local, boolean serverAvailable) {
        return new GuideService(
                ACTOR,
                local,
                new GuideRemoteEndpoint() {
                    @Override public boolean serverModelAvailable() { return serverAvailable; }
                    @Override public boolean serverToolsAvailable() { return serverAvailable; }
                    @Override public boolean ask(
                            UUID requestId,
                            String sessionId,
                            String question,
                            Consumer<AgentEvent> events) {
                        if (!serverAvailable) return false;
                        events.accept(new AgentEvent.StateChanged(AgentState.MODEL_WAIT));
                        return true;
                    }
                    @Override public boolean cancel(UUID requestId) { return true; }
                    @Override public void disconnect() {}
                },
                (capabilities, correlation) -> new ToolResult.Success<>(
                        ToolInvocationContext.developmentConsole(correlation)),
                Runnable::run,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                new Gson());
    }

    private static GuideSessionSnapshot session(GuideService service, String id) {
        return service.snapshot().sessions().stream()
                .filter(session -> session.sessionId().equals(id))
                .findFirst().orElseThrow();
    }

    private static GuideRequestSnapshot request(GuideService service, String session, UUID id) {
        return session(service, session).requests().stream()
                .filter(request -> request.requestId().equals(id))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> T success(ToolResult<T> result) {
        return ((ToolResult.Success<T>) assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    @SuppressWarnings("unchecked")
    private static <T> ToolResult.Failure<T> failure(ToolResult<T> result) {
        return (ToolResult.Failure<T>) assertInstanceOf(ToolResult.Failure.class, result);
    }

    private static final class ProfileLocal implements GuideLocalEndpoint {
        private List<GuideClientModelProfile> profiles = List.of(
                new GuideClientModelProfile("a", "Model A", true, true, "model-a", null),
                new GuideClientModelProfile("b", "Model B", true, true, "model-b", null),
                new GuideClientModelProfile(
                        "broken", "Broken", true, false, "broken-model",
                        new GuideFailure("invalid_model_config", "context window missing")));
        private final Map<UUID, String> profileByRequest = new LinkedHashMap<>();
        private final Map<UUID, Consumer<AgentEvent>> pending = new LinkedHashMap<>();

        @Override public String defaultProfileId() { return "a"; }
        @Override public List<GuideClientModelProfile> profiles() { return profiles; }
        @Override public Set<ContextCapability> requiredContext() { return Set.of(); }
        @Override public Set<ContextCapability> requiredContext(String profileId) { return Set.of(); }

        @Override
        public CompletableFuture<AgentResult> ask(
                UUID actor,
                String sessionId,
                UUID requestId,
                String question,
                ToolInvocationContext context,
                Consumer<AgentEvent> events) {
            throw new AssertionError("profile-aware dispatch required");
        }

        @Override
        public CompletableFuture<AgentResult> ask(
                String profileId,
                UUID actor,
                String sessionId,
                UUID requestId,
                String question,
                ToolInvocationContext context,
                Consumer<AgentEvent> events) {
            profileByRequest.put(requestId, profileId);
            pending.put(requestId, events);
            events.accept(new AgentEvent.StateChanged(AgentState.MODEL_WAIT));
            return new CompletableFuture<>();
        }

        @Override public boolean cancel(UUID actor, String sessionId) { return true; }
        @Override public void clearSession(UUID actor, String sessionId) {}
        @Override public void clearActor(UUID actor) {}

        private void complete(UUID requestId, String text) {
            pending.get(requestId).accept(new AgentEvent.FinalText(text));
        }

        private void fail(UUID requestId, String code, String message) {
            pending.get(requestId).accept(new AgentEvent.Failed(code, message));
        }

        private void checkpoint(UUID requestId, ContextCheckpoint checkpoint) {
            pending.get(requestId).accept(new AgentEvent.ContextCompacted(checkpoint));
        }

        private void remove(String profileId) {
            profiles = profiles.stream().filter(profile -> !profile.id().equals(profileId)).toList();
        }
    }
}
