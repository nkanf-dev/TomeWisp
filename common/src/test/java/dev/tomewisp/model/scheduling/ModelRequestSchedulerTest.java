package dev.tomewisp.model.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelContent;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelMessage;
import dev.tomewisp.model.ModelRateLimitException;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.ModelUsage;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class ModelRequestSchedulerTest {
    @Test
    void dispatchesDifferentSessionsWithoutAnArtificialConcurrencyCap() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient delegate = (request, events, cancellation) -> {
            calls.incrementAndGet();
            return new CompletableFuture<>();
        };
        ModelRequestScheduler scheduler = new ModelRequestScheduler(delegate);

        scheduler.complete(request("one"), event -> {}, new CancellationSignal());
        scheduler.complete(request("two"), event -> {}, new CancellationSignal());

        assertEquals(2, calls.get());
        assertEquals(0, scheduler.queuedRequests());
    }

    @Test
    void requeues429AndHonorsRetryDelayUntilSuccess() throws Exception {
        Deque<CompletableFuture<ModelTurn>> turns = new ArrayDeque<>();
        turns.add(CompletableFuture.failedFuture(
                new ModelRateLimitException("limited", Duration.ofMillis(30))));
        turns.add(CompletableFuture.completedFuture(turn("resumed")));
        AtomicInteger calls = new AtomicInteger();
        ModelClient delegate = (request, events, cancellation) -> {
            calls.incrementAndGet();
            return turns.removeFirst();
        };
        ModelRequestScheduler scheduler = new ModelRequestScheduler(delegate);
        List<ModelEvent> events = new ArrayList<>();

        ModelTurn result = scheduler.complete(request("one"), events::add, new CancellationSignal())
                .get(2, TimeUnit.SECONDS);

        assertEquals("resumed", result.text());
        assertEquals(2, calls.get());
        assertTrue(events.stream().anyMatch(event -> event instanceof ModelEvent.RateLimited));
        assertEquals(
                List.of(1, 2),
                events.stream()
                        .filter(ModelEvent.AttemptStarted.class::isInstance)
                        .map(ModelEvent.AttemptStarted.class::cast)
                        .map(ModelEvent.AttemptStarted::attempt)
                        .toList());
    }

    @Test
    void preservesTransportAttemptBudgetWhileReplacingItsLocalAttemptNumber() {
        long attemptTimeoutMillis = 10_000;
        ModelRequestScheduler scheduler = new ModelRequestScheduler(
                (request, events, cancellation) -> {
                    events.accept(new ModelEvent.AttemptStarted(99, attemptTimeoutMillis));
                    return CompletableFuture.completedFuture(turn("done"));
                });
        List<ModelEvent> events = new ArrayList<>();

        scheduler.complete(request("one"), events::add, new CancellationSignal()).join();

        List<ModelEvent.AttemptStarted> attempts = events.stream()
                .filter(ModelEvent.AttemptStarted.class::isInstance)
                .map(ModelEvent.AttemptStarted.class::cast)
                .toList();
        assertEquals(2, attempts.size());
        assertEquals(1, attempts.getFirst().attempt());
        assertEquals(null, attempts.getFirst().attemptTimeoutMillis());
        assertEquals(1, attempts.getLast().attempt());
        assertEquals(attemptTimeoutMillis, attempts.getLast().attemptTimeoutMillis());
    }

    @Test
    void queuedCancellationDoesNotDispatchAfterCooldown() throws Exception {
        CompletableFuture<ModelTurn> firstAttempt = CompletableFuture.failedFuture(
                new ModelRateLimitException("limited", Duration.ofMillis(200)));
        AtomicInteger calls = new AtomicInteger();
        ModelClient delegate = (request, events, cancellation) -> {
            calls.incrementAndGet();
            return firstAttempt;
        };
        ModelRequestScheduler scheduler = new ModelRequestScheduler(delegate);
        CancellationSignal cancellation = new CancellationSignal();
        CompletableFuture<ModelTurn> result =
                scheduler.complete(request("one"), event -> {}, cancellation);
        cancellation.cancel();

        assertTrue(result.isCompletedExceptionally());
        Thread.sleep(300);
        assertEquals(1, calls.get());
        assertFalse(scheduler.queuedRequests() > 0);
    }

    @Test
    void delaysFreshContextCaptureWhileEndpointCooldownIsKnown() throws Exception {
        Deque<CompletableFuture<ModelTurn>> turns = new ArrayDeque<>();
        turns.add(CompletableFuture.failedFuture(
                new ModelRateLimitException("limited", Duration.ofMillis(80))));
        turns.add(CompletableFuture.completedFuture(turn("resumed")));
        ModelRequestScheduler scheduler = new ModelRequestScheduler(
                (request, events, cancellation) -> turns.removeFirst());
        scheduler.complete(request("one"), event -> {}, new CancellationSignal());

        CompletableFuture<Void> ready = scheduler.awaitReady(new CancellationSignal());
        assertFalse(ready.isDone());
        ready.get(2, TimeUnit.SECONDS);
        assertTrue(ready.isDone());
    }

    private static ModelRequest request(String session) {
        return new ModelRequest(
                "system",
                List.of(ModelMessage.userText("question")),
                List.of(),
                false,
                session);
    }

    private static ModelTurn turn(String text) {
        return new ModelTurn(
                "test",
                "test",
                List.of(new ModelContent.Text(text)),
                "end_turn",
                ModelUsage.empty());
    }
}
