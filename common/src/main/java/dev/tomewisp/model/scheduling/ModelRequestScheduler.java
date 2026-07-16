package dev.tomewisp.model.scheduling;

import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelFailure;
import dev.tomewisp.model.ModelRateLimitException;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelTurn;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class ModelRequestScheduler implements ModelClient {
    private final ModelClient delegate;
    private final Map<String, ArrayDeque<Pending>> queues = new HashMap<>();
    private final ArrayDeque<String> rotation = new ArrayDeque<>();
    private final Set<String> inRotation = new HashSet<>();
    private long gateUntilNanos;
    private boolean wakeScheduled;

    public ModelRequestScheduler(ModelClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<ModelTurn> complete(
            ModelRequest request,
            Consumer<ModelEvent> events,
            CancellationSignal cancellation) {
        Pending pending = new Pending(request, events, cancellation);
        cancellation.onCancel(() -> cancelQueued(pending));
        enqueue(pending, false);
        return pending.result;
    }

    public synchronized int queuedRequests() {
        return queues.values().stream().mapToInt(ArrayDeque::size).sum();
    }

    private void enqueue(Pending pending, boolean front) {
        List<Pending> dispatch;
        synchronized (this) {
            if (pending.cancellation.isCancelled() || pending.result.isDone()) {
                return;
            }
            ArrayDeque<Pending> queue =
                    queues.computeIfAbsent(pending.request.sessionKey(), ignored -> new ArrayDeque<>());
            if (front) {
                queue.addFirst(pending);
            } else {
                queue.addLast(pending);
            }
            addRotation(pending.request.sessionKey());
            dispatch = drainLocked();
        }
        dispatch.forEach(this::dispatch);
    }

    private List<Pending> drainLocked() {
        long remaining = gateUntilNanos - System.nanoTime();
        if (remaining > 0) {
            scheduleWakeLocked(remaining);
            return List.of();
        }
        gateUntilNanos = 0;
        List<Pending> dispatch = new ArrayList<>();
        while (!rotation.isEmpty()) {
            String session = rotation.removeFirst();
            inRotation.remove(session);
            ArrayDeque<Pending> queue = queues.get(session);
            if (queue == null || queue.isEmpty()) {
                queues.remove(session);
                continue;
            }
            Pending pending = queue.removeFirst();
            if (queue.isEmpty()) {
                queues.remove(session);
            } else {
                addRotation(session);
            }
            if (!pending.cancellation.isCancelled() && !pending.result.isDone()) {
                dispatch.add(pending);
            }
        }
        return dispatch;
    }

    private void dispatch(Pending pending) {
        pending.attempt++;
        delegate.complete(pending.request, pending.events, pending.cancellation)
                .whenComplete((turn, throwable) -> {
                    Throwable cause = unwrap(throwable);
                    if (cause instanceof ModelRateLimitException limited
                            && !pending.cancellation.isCancelled()) {
                        Duration delay = limited.retryAfter() == null
                                ? fallbackDelay(pending.attempt)
                                : limited.retryAfter();
                        pending.events.accept(
                                new ModelEvent.RateLimited(delay.toMillis(), pending.attempt));
                        closeGate(delay);
                        enqueue(pending, true);
                    } else if (throwable != null) {
                        pending.result.completeExceptionally(cause);
                    } else {
                        pending.result.complete(turn);
                    }
                });
    }

    private void closeGate(Duration delay) {
        synchronized (this) {
            long candidate = System.nanoTime() + Math.max(0, delay.toNanos());
            gateUntilNanos = Math.max(gateUntilNanos, candidate);
            scheduleWakeLocked(Math.max(0, gateUntilNanos - System.nanoTime()));
        }
    }

    private void scheduleWakeLocked(long delayNanos) {
        if (wakeScheduled) {
            return;
        }
        wakeScheduled = true;
        Thread.ofVirtual().name("tomewisp-model-rate-wait").start(() -> {
            try {
                long remaining = delayNanos;
                while (remaining > 0) {
                    long millis = Math.max(1, Math.min(1000, Duration.ofNanos(remaining).toMillis()));
                    Thread.sleep(millis);
                    synchronized (ModelRequestScheduler.this) {
                        remaining = gateUntilNanos - System.nanoTime();
                    }
                }
                List<Pending> dispatch;
                synchronized (ModelRequestScheduler.this) {
                    wakeScheduled = false;
                    dispatch = drainLocked();
                }
                dispatch.forEach(this::dispatch);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void cancelQueued(Pending pending) {
        synchronized (this) {
            ArrayDeque<Pending> queue = queues.get(pending.request.sessionKey());
            if (queue != null) {
                queue.remove(pending);
                if (queue.isEmpty()) {
                    queues.remove(pending.request.sessionKey());
                    rotation.remove(pending.request.sessionKey());
                    inRotation.remove(pending.request.sessionKey());
                }
            }
        }
        pending.result.completeExceptionally(new ModelClientException(
                new ModelFailure("agent_cancelled", "Agent request was cancelled", null)));
    }

    private void addRotation(String session) {
        if (inRotation.add(session)) {
            rotation.addLast(session);
        }
    }

    private static Duration fallbackDelay(int attempt) {
        long seconds = Math.min(60, 1L << Math.min(6, Math.max(0, attempt - 1)));
        return Duration.ofSeconds(seconds);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while ((current instanceof CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class Pending {
        private final ModelRequest request;
        private final Consumer<ModelEvent> events;
        private final CancellationSignal cancellation;
        private final CompletableFuture<ModelTurn> result = new CompletableFuture<>();
        private int attempt;

        private Pending(
                ModelRequest request,
                Consumer<ModelEvent> events,
                CancellationSignal cancellation) {
            this.request = request;
            this.events = events;
            this.cancellation = cancellation;
        }
    }
}
