package dev.tomewisp.model.http;

import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClientException;
import dev.tomewisp.model.ModelFailure;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.net.HttpTransport;
import dev.tomewisp.net.HttpExchangeRequest;
import dev.tomewisp.net.HttpResponseHeaders;
import dev.tomewisp.net.HttpTransportPolicy;
import dev.tomewisp.net.JdkHttpTransport;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public final class HttpModelTransport {
    @FunctionalInterface
    public interface ResponseDecoder<T> {
        T decode(int status, HttpResponseHeaders headers, InputStream body) throws IOException;
    }

    private final HttpTransport transport;

    public HttpModelTransport(ModelConfig config) {
        transport = new JdkHttpTransport(new HttpTransportPolicy(
                config.connectTimeout(),
                "tomewisp-model-http"));
    }

    public <T> CompletableFuture<T> execute(
            HttpExchangeRequest request,
            CancellationSignal cancellation,
            ResponseDecoder<T> decoder) {
        CompletableFuture<T> result = new CompletableFuture<>();
        transport.execute(request, cancellation, decoder::decode).whenComplete((value, failure) -> {
            if (failure == null) {
                result.complete(value);
                return;
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof ModelClientException exception) {
                result.completeExceptionally(exception);
            } else {
                boolean cancelled = cancellation.isCancelled()
                        || cause instanceof java.util.concurrent.CancellationException;
                boolean timedOut = cause instanceof HttpTimeoutException
                        || cause instanceof TimeoutException;
                result.completeExceptionally(new ModelClientException(new ModelFailure(
                        cancelled
                                ? "agent_cancelled"
                                : timedOut ? "model_timeout" : "model_transport_error",
                        cancelled
                                ? "Model request was cancelled"
                                : timedOut
                                        ? "Model request timed out"
                                        : "Model transport is unavailable",
                        null)));
            }
        });
        return result;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
