package dev.tomewisp.model.http;

import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelFailure;
import dev.tomewisp.model.config.ModelConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;

public final class HttpModelTransport {
    @FunctionalInterface
    public interface ResponseDecoder<T> {
        T decode(int status, HttpHeaders headers, InputStream body) throws IOException;
    }

    private final HttpClient client;

    public HttpModelTransport(ModelConfig config) {
        client = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public <T> CompletableFuture<T> execute(
            HttpRequest request,
            CancellationSignal cancellation,
            ResponseDecoder<T> decoder) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(decoder, "decoder");
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<InputStream> activeBody = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().name("tomewisp-model-http").unstarted(() -> {
            try {
                result.complete(executeWithConnectRetry(request, cancellation, decoder, activeBody));
            } catch (Throwable throwable) {
                if (throwable instanceof ModelClientException exception) {
                    result.completeExceptionally(exception);
                } else {
                    result.completeExceptionally(new ModelClientException(new ModelFailure(
                            cancellation.isCancelled()
                                    ? "agent_cancelled"
                                    : "model_transport_error",
                            safeMessage(throwable),
                            null)));
                }
            }
        });
        cancellation.onCancel(() -> {
            InputStream body = activeBody.getAndSet(null);
            if (body != null) {
                try {
                    body.close();
                } catch (IOException ignored) {
                    // Cancellation is already the authoritative outcome.
                }
            }
            worker.interrupt();
        });
        worker.start();
        return result;
    }

    private <T> T executeWithConnectRetry(
            HttpRequest request,
            CancellationSignal cancellation,
            ResponseDecoder<T> decoder,
            AtomicReference<InputStream> activeBody)
            throws IOException, InterruptedException {
        int attempt = 0;
        while (true) {
            cancellation.throwIfCancelled();
            attempt++;
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException exception) {
                if (attempt < 4 && retryableBeforeResponse(exception)) {
                    Thread.sleep(250L * attempt);
                    continue;
                }
                throw exception;
            }
            InputStream body = response.body();
            activeBody.set(body);
            try (body) {
                return decoder.decode(
                        response.statusCode(),
                        response.headers(),
                        body);
            } finally {
                activeBody.compareAndSet(body, null);
            }
        }
    }

    private static boolean retryableBeforeResponse(IOException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConnectException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof SSLHandshakeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
