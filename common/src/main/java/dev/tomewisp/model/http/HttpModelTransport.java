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
import java.util.concurrent.CompletableFuture;

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
                result.completeExceptionally(new ModelClientException(new ModelFailure(
                        cancellation.isCancelled()
                                ? "agent_cancelled"
                                : "model_transport_error",
                        safeMessage(cause),
                        null)));
            }
        });
        return result;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null) {
            return completion.getCause();
        }
        return throwable;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
