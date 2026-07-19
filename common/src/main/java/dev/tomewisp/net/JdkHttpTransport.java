package dev.tomewisp.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Shared JDK transport. Domain adapters decide endpoints, headers, and response semantics. */
public final class JdkHttpTransport implements HttpTransport {
    private final HttpClient client;
    private final HttpTransportPolicy policy;
    private final Executor decoderExecutor;

    public JdkHttpTransport(HttpTransportPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        client = HttpClient.newBuilder()
                .connectTimeout(policy.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        decoderExecutor = command -> Thread.ofVirtual()
                .name(policy.decoderThreadName())
                .start(command);
    }

    @Override
    public <T> CompletableFuture<T> execute(
            HttpExchangeRequest request,
            HttpCancellation cancellation,
            ResponseDecoder<T> decoder) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(decoder, "decoder");
        if (cancellation.isCancelled()) {
            return CompletableFuture.failedFuture(
                    new CancellationException("HTTP request cancelled"));
        }
        AtomicBoolean settled = new AtomicBoolean();
        AtomicReference<InputStream> activeBody = new AtomicReference<>();
        AtomicReference<CompletableFuture<?>> activeDecoder = new AtomicReference<>();
        AtomicReference<Thread> activeWatchdog = new AtomicReference<>();
        CompletableFuture<T> result = new CompletableFuture<>();
        long deadlineNanos = System.nanoTime() + request.timeout().toNanos();
        HttpRequest.Builder encoded = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout());
        request.headers().forEach((name, values) ->
                values.forEach(value -> encoded.header(name, value)));
        byte[] requestBody = request.body();
        encoded.method(
                request.method(),
                requestBody.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(requestBody));
        CompletableFuture<HttpResponse<InputStream>> response = client.sendAsync(
                encoded.build(), HttpResponse.BodyHandlers.ofInputStream());

        java.util.function.Consumer<Throwable> fail = failure -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            response.cancel(true);
            CompletableFuture<?> decoding = activeDecoder.get();
            if (decoding != null) {
                decoding.cancel(true);
            }
            close(activeBody.getAndSet(null));
            Thread watchdog = activeWatchdog.get();
            if (watchdog != null && watchdog != Thread.currentThread()) {
                watchdog.interrupt();
            }
            result.completeExceptionally(failure);
        };
        java.util.function.Consumer<T> succeed = value -> {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            Thread watchdog = activeWatchdog.get();
            if (watchdog != null && watchdog != Thread.currentThread()) {
                watchdog.interrupt();
            }
            result.complete(value);
        };

        response.whenComplete((received, failure) -> {
            if (failure != null) {
                fail.accept(unwrap(failure));
                return;
            }
            InputStream body = received.body();
            if (settled.get()) {
                close(body);
                return;
            }
            activeBody.set(body);
            if (settled.get()) {
                close(activeBody.getAndSet(null));
                return;
            }
            CompletableFuture<T> decoding = CompletableFuture.supplyAsync(() -> {
                try (body) {
                    if (cancellation.isCancelled() || settled.get()) {
                        throw new CancellationException("HTTP request cancelled");
                    }
                    return decoder.decode(
                            received.statusCode(),
                            new HttpResponseHeaders(received.headers().map()),
                            body);
                } catch (IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                } finally {
                    activeBody.compareAndSet(body, null);
                }
            }, decoderExecutor);
            activeDecoder.set(decoding);
            decoding.whenComplete((value, decodeFailure) -> {
                if (decodeFailure == null) {
                    succeed.accept(value);
                } else {
                    fail.accept(unwrap(decodeFailure));
                }
            });
        });

        Thread watchdog = Thread.ofVirtual().name(policy.decoderThreadName() + "-watchdog").unstarted(() -> {
            try {
                while (!settled.get()) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        fail.accept(new HttpTimeoutException("HTTP response timed out"));
                        return;
                    }
                    Thread.sleep(java.time.Duration.ofNanos(remaining));
                }
            } catch (InterruptedException ignored) {
                // Decoder completion or explicit cancellation owns the terminal result.
            }
        });
        activeWatchdog.set(watchdog);
        watchdog.start();
        cancellation.onCancel(() -> fail.accept(
                new CancellationException("HTTP request cancelled")));
        return result;
    }

    private static void close(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // A terminal outcome already owns the exchange.
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
