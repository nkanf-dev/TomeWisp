package dev.openallay.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.agent.AgentEvent;
import dev.openallay.agent.context.ContextCheckpoint;
import dev.openallay.guide.GuideToolMessage;
import dev.openallay.model.ModelEvent;
import dev.openallay.model.ModelUsage;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class ServerAgentEventCodecTest {
    private final ServerAgentEventCodec codec = new ServerAgentEventCodec(new Gson());

    @Test
    void roundTripsObservableEventsAndTerminalFlags() {
        UUID request = UUID.randomUUID();
        assertEquals(
                "hello",
                assertInstanceOf(
                                ModelEvent.TextDelta.class,
                                assertInstanceOf(
                                                AgentEvent.ModelProgress.class,
                                                codec.decode(codec.encode(
                                                        request,
                                                        new AgentEvent.ModelProgress(
                                                                new ModelEvent.TextDelta("hello"))),
                                                        request))
                                        .event())
                        .text());
        assertEquals(
                new ModelUsage(4, 2, 1),
                assertInstanceOf(
                                ModelEvent.UsageUpdate.class,
                                assertInstanceOf(
                                                AgentEvent.ModelProgress.class,
                                                codec.decode(codec.encode(
                                                        request,
                                                        new AgentEvent.ModelProgress(
                                                                new ModelEvent.UsageUpdate(
                                                                        new ModelUsage(4, 2, 1)))),
                                                        request))
                                        .event())
                        .usage());
        ServerAgentEventPayload encodedAttempt = codec.encode(
                request,
                new AgentEvent.ModelProgress(
                        new ModelEvent.AttemptStarted(2, 12_345L)));
        assertEquals(
                Set.of("attempt", "attemptTimeoutMillis"),
                JsonParser.parseString(encodedAttempt.eventJson())
                        .getAsJsonObject()
                        .keySet());
        ModelEvent.AttemptStarted attempt = assertInstanceOf(
                ModelEvent.AttemptStarted.class,
                assertInstanceOf(
                                AgentEvent.ModelProgress.class,
                                codec.decode(encodedAttempt, request))
                        .event());
        assertEquals(2, attempt.attempt());
        assertEquals(12_345L, attempt.attemptTimeoutMillis());
        assertInstanceOf(
                ModelEvent.ResponseStarted.class,
                assertInstanceOf(
                                AgentEvent.ModelProgress.class,
                                codec.decode(codec.encode(
                                        request,
                                        new AgentEvent.ModelProgress(
                                                new ModelEvent.ResponseStarted())),
                                        request))
                        .event());

        AgentEvent.ToolStarted started = assertInstanceOf(
                AgentEvent.ToolStarted.class,
                codec.decode(
                        codec.encode(request, new AgentEvent.ToolStarted(
                                "call-1",
                                "openallay:get_recipe",
                                List.of(GuideToolMessage.of(
                                        GuideToolMessage.Key.INVOCATION_GET_RECIPE_EXACT,
                                        "minecraft:iron_block")))),
                        request));
        assertEquals("call-1", started.invocationId());
        assertEquals(
                List.of(GuideToolMessage.of(
                        GuideToolMessage.Key.INVOCATION_GET_RECIPE_EXACT,
                        "minecraft:iron_block")),
                started.presentationMessages());

        AgentEvent.ToolCompleted completed = assertInstanceOf(
                AgentEvent.ToolCompleted.class,
                codec.decode(
                        codec.encode(request, new AgentEvent.ToolCompleted(
                                "call-1",
                                "openallay:get_recipe",
                                false,
                                new JsonObject())),
                        request));
        assertEquals("call-1", completed.invocationId());

        ContextCheckpoint checkpoint = new ContextCheckpoint(
                UUID.randomUUID(), 0, 2, "a".repeat(64), "model", 1, 1,
                Instant.EPOCH, ContextCheckpoint.Status.SUCCEEDED, "{}", null, null, 42);
        AgentEvent.ContextCompacted compacted = assertInstanceOf(
                AgentEvent.ContextCompacted.class,
                codec.decode(codec.encode(
                        request, new AgentEvent.ContextCompacted(checkpoint)), request));
        assertEquals(checkpoint, compacted.checkpoint());

        ServerAgentEventPayload terminal =
                codec.encode(request, new AgentEvent.FinalText("done"));
        assertEquals(true, terminal.terminal());
        assertEquals("done", assertInstanceOf(
                AgentEvent.FinalText.class, codec.decode(terminal, request)).text());
    }

    @Test
    void rejectsUnknownMismatchedAndInconsistentEvents() {
        UUID request = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new ServerAgentEventPayload(
                        BridgeProtocol.VERSION, request, "future_event", "{}", false),
                request));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new ServerAgentEventPayload(
                        BridgeProtocol.VERSION,
                        request,
                        "model_attempt_started",
                        "{\"attempt\":1,\"provider\":\"secret\"}",
                        false),
                request));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new ServerAgentEventPayload(
                        BridgeProtocol.VERSION,
                        request,
                        "model_attempt_started",
                        "{\"attempt\":1,\"deadlineEpochMillis\":12345}",
                        false),
                request));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new ServerAgentEventPayload(
                        BridgeProtocol.VERSION, request, "context_compacted", "{}", false),
                request));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new ServerAgentEventPayload(
                        BridgeProtocol.VERSION,
                        request,
                        "tool_started",
                        "{\"toolId\":\"openallay:get_recipe\"}",
                        false),
                request));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new ServerAgentEventPayload(
                        BridgeProtocol.VERSION,
                        request,
                        "tool_started",
                        "{\"invocationId\":\"call-1\",\"toolId\":\"openallay:get_recipe\","
                                + "\"presentationMessages\":[{\"key\":\"RESULT_COMPLETED\","
                                + "\"arguments\":[],\"arbitraryTranslationKey\":\"evil.key\"}]}",
                        false),
                request));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                codec.encode(request, new AgentEvent.FinalText("done")), UUID.randomUUID()));
        ServerAgentEventPayload finalText = codec.encode(request, new AgentEvent.FinalText("done"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new ServerAgentEventPayload(
                        finalText.version(),
                        finalText.requestId(),
                        finalText.eventType(),
                        finalText.eventJson(),
                        false),
                request));
    }

    @Test
    void bridgeCodecAcceptsDedicatedServerAgentCancelOnlyAtCurrentVersion() {
        UUID request = UUID.randomUUID();
        BridgeJsonCodec json = new BridgeJsonCodec();
        ServerAgentCancelPayload value = new ServerAgentCancelPayload(BridgeProtocol.VERSION, request);

        assertEquals(request, json.decode(
                json.encode(value), ServerAgentCancelPayload.class).requestId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerAgentCancelPayload(BridgeProtocol.VERSION + 1, request));
    }
}
