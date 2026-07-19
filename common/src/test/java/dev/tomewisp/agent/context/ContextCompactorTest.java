package dev.tomewisp.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelFailure;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.ModelUsage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ContextCompactorTest {
    private static final String SUMMARY = """
            {"goals":["build"],"preferences":[],"completedTopics":[],
             "currentTasks":["recipe"],"decisions":[],"unresolvedQuestions":[],
             "evidenceReferences":["viewer:jei/ref"]}
            """;

    @Test
    void returnsOriginalProjectionWithoutCallingModelWhenItFits() {
        FakeModel model = new FakeModel(textTurn(SUMMARY));
        ContextCompactor.Result result = compactor(model, new ContextBudget(10_000, 100))
                .compact("system", List.of(ModelMessage.userText("hello")), 0, List.of(),
                        true, "actor:main", new CancellationSignal())
                .join();

        assertTrue(result.successful());
        assertEquals(ContextProjection.Kind.ORIGINAL, result.projection().kind());
        assertNull(result.checkpoint());
        assertEquals(0, model.requests.size());
    }

    @Test
    void usesSameSchedulingKeyAndCreatesSourceHashedDerivedSummary() {
        FakeModel model = new FakeModel(textTurn(SUMMARY));
        List<ModelMessage> messages = List.of(
                ModelMessage.userText("a".repeat(500)),
                ModelMessage.userText("b".repeat(500)),
                ModelMessage.userText("current"));
        ContextCompactor compactor = compactor(model, new ContextBudget(1_200, 100));

        ContextCompactor.Result result = compactor.compact(
                        "system", messages, 2, List.of(), true, "actor:recipes",
                        new CancellationSignal())
                .join();

        assertTrue(result.successful());
        assertEquals(ContextProjection.Kind.SUMMARIZED, result.projection().kind());
        assertEquals("actor:recipes", model.requests.getFirst().sessionKey());
        assertFalse(model.requests.getFirst().stream());
        assertTrue(result.projection().messages().getFirst().content().stream()
                .map(ModelContent.Text.class::cast)
                .anyMatch(text -> text.text().contains("NOT factual evidence")));
        ContextCheckpoint checkpoint = result.checkpoint();
        assertNotNull(checkpoint);
        assertEquals(ContextCheckpoint.Status.SUCCEEDED, checkpoint.status());
        assertEquals("test-model", checkpoint.modelIdentifier());
        assertTrue(compactor.matches(checkpoint, messages));

        List<ModelMessage> changed = new ArrayList<>(messages);
        changed.set(0, ModelMessage.userText("changed"));
        assertFalse(compactor.matches(checkpoint, changed));

        List<ModelMessage> nextRequest = new ArrayList<>(messages);
        nextRequest.add(ModelMessage.userText("next"));
        assertTrue(compactor.reuse(
                        checkpoint, "system", nextRequest, messages.size(), List.of())
                .isPresent());
        ContextCompactor otherModel = new ContextCompactor(
                model,
                new Gson(),
                new Utf8ContextTokenEstimator(),
                new ToolResultContextReducer(),
                new ContextBudget(1_200, 100),
                "other-model",
                Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC));
        assertTrue(otherModel.reuse(
                        checkpoint, "system", nextRequest, messages.size(), List.of())
                .isPresent());
        ContextCheckpoint futureSchema = new ContextCheckpoint(
                checkpoint.checkpointId(),
                checkpoint.sourceFromIndex(),
                checkpoint.sourceToIndexExclusive(),
                checkpoint.sourceHash(),
                checkpoint.modelIdentifier(),
                checkpoint.promptVersion(),
                99,
                checkpoint.createdAt(),
                checkpoint.status(),
                checkpoint.summary(),
                checkpoint.failureCode(),
                checkpoint.failureMessage(),
                checkpoint.estimatedProjectionTokens());
        assertTrue(compactor.reuse(
                        futureSchema, "system", nextRequest, messages.size(), List.of())
                .isEmpty());
        nextRequest.set(0, ModelMessage.userText("stale"));
        assertTrue(compactor.reuse(
                        checkpoint, "system", nextRequest, messages.size(), List.of())
                .isEmpty());
    }

    @Test
    void malformedOrFailedSummaryReturnsStructuredTerminalFailure() {
        FakeModel malformed = new FakeModel(textTurn("not-json"));
        ContextCompactor.Result malformedResult = compactLarge(malformed);
        assertFalse(malformedResult.successful());
        assertEquals("context_compaction_failed", malformedResult.failureCode());
        assertEquals("summary_malformed", malformedResult.checkpoint().failureCode());

        FakeModel failed = new FakeModel(new ModelClientException(
                new ModelFailure("provider_down", "redacted", 503)));
        ContextCompactor.Result failedResult = compactLarge(failed);
        assertFalse(failedResult.successful());
        assertEquals("provider_down", failedResult.checkpoint().failureCode());
    }

    @Test
    void checkpointCodecIsStrictAndRoundTripsFailures() {
        ContextCheckpoint checkpoint = new ContextCheckpoint(
                java.util.UUID.randomUUID(), 0, 2, "a".repeat(64), "model", 1, 1,
                Instant.EPOCH, ContextCheckpoint.Status.FAILED, null,
                "summary_malformed", "bad", 900);
        ContextCheckpointCodec codec = new ContextCheckpointCodec();

        assertEquals(checkpoint, codec.decode(codec.encode(checkpoint)));
        JsonObject unknown = JsonParser.parseString(codec.encode(checkpoint)).getAsJsonObject();
        unknown.addProperty("unknown", true);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknown.toString()));
    }

    @Test
    void cancellationBeforeCompactionSuppressesEveryModelCall() {
        FakeModel model = new FakeModel(textTurn(SUMMARY));
        CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();

        ModelClientException failure = assertThrows(
                ModelClientException.class,
                () -> compactor(model, new ContextBudget(1_200, 100))
                        .compact("system", List.of(ModelMessage.userText("x")), 0,
                                List.of(), true, "actor:main", cancellation));

        assertEquals("agent_cancelled", failure.failure().code());
        assertEquals(0, model.requests.size());
    }

    private static ContextCompactor.Result compactLarge(FakeModel model) {
        return compactor(model, new ContextBudget(1_200, 100))
                .compact("system", List.of(
                                ModelMessage.userText("a".repeat(500)),
                                ModelMessage.userText("b".repeat(500)),
                                ModelMessage.userText("current")),
                        2, List.of(), true, "actor:main", new CancellationSignal())
                .join();
    }

    private static ContextCompactor compactor(FakeModel model, ContextBudget budget) {
        return new ContextCompactor(
                model,
                new Gson(),
                new Utf8ContextTokenEstimator(),
                new ToolResultContextReducer(),
                budget,
                "test-model",
                Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC));
    }

    private static ModelTurn textTurn(String text) {
        return new ModelTurn("test", "test-model", List.of(new ModelContent.Text(text)),
                "end_turn", ModelUsage.empty());
    }

    private static final class FakeModel implements ModelClient {
        private final Object outcome;
        private final List<ModelRequest> requests = new ArrayList<>();

        private FakeModel(Object outcome) {
            this.outcome = outcome;
        }

        @Override
        public CompletableFuture<ModelTurn> complete(
                ModelRequest request,
                Consumer<ModelEvent> events,
                CancellationSignal cancellation) {
            requests.add(request);
            if (outcome instanceof RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture((ModelTurn) outcome);
        }
    }
}
