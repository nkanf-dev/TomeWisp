package dev.tomewisp.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
        AtomicReference<InputStream> activeBody = new AtomicReference<>();
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
        CompletableFuture<T> result = response.thenApplyAsync(received -> {
            InputStream body = received.body();
            activeBody.set(body);
            try (body) {
                if (cancellation.isCancelled()) {
                    throw new CancellationException("HTTP request cancelled");
                }
                return decoder.decode(
                        received.statusCode(),
                        new HttpResponseHeaders(received.headers().map()),
                        body);
            } catch (IOException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            } finally {
                activeBody.compareAndSet(body, null);
            }
        }, decoderExecutor);
        cancellation.onCancel(() -> {
            response.cancel(true);
            InputStream body = activeBody.getAndSet(null);
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // Cancellation already determines the outcome.
                }
            }
        });
        return result;
    }
}
