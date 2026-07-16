package dev.tomewisp.model.anthropic;

import com.google.gson.Gson;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ModelClient;
import dev.tomewisp.model.ModelEvent;
import dev.tomewisp.model.ModelRequest;
import dev.tomewisp.model.ModelTurn;
import dev.tomewisp.model.config.ModelConfig;
import dev.tomewisp.model.http.HttpModelTransport;
import dev.tomewisp.model.http.ModelHttpErrors;
import dev.tomewisp.model.http.SseParser;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class AnthropicMessagesClient implements ModelClient {
    private final ModelConfig config;
    private final AnthropicJsonCodec codec;
    private final HttpModelTransport transport;

    public AnthropicMessagesClient(ModelConfig config, Gson gson) {
        this.config = Objects.requireNonNull(config, "config");
        codec = new AnthropicJsonCodec(gson);
        transport = new HttpModelTransport(config);
    }

    @Override
    public CompletableFuture<ModelTurn> complete(
            ModelRequest request,
            Consumer<ModelEvent> events,
            CancellationSignal cancellation) {
        HttpRequest httpRequest = HttpRequest.newBuilder(config.baseUri().resolve("messages"))
                .timeout(config.requestTimeout())
                .header("x-api-key", config.apiKey().reveal())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(codec.requestBody(config, request)))
                .build();
        return transport.execute(httpRequest, cancellation, (status, headers, body) -> {
            ModelHttpErrors.requireSuccess(status, headers, body);
            return request.stream()
                            || headers.firstValue("content-type")
                                    .orElse("")
                                    .contains("text/event-stream")
                    ? decodeStream(body, events, cancellation)
                    : codec.parseTurn(new String(body.readAllBytes(), StandardCharsets.UTF_8), events);
        });
    }

    private static ModelTurn decodeStream(
            InputStream body,
            Consumer<ModelEvent> events,
            CancellationSignal cancellation)
            throws java.io.IOException {
        AnthropicStreamAccumulator accumulator = new AnthropicStreamAccumulator(events);
        SseParser parser = new SseParser(accumulator::accept);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = body.read(buffer)) >= 0) {
            cancellation.throwIfCancelled();
            parser.accept(java.util.Arrays.copyOf(buffer, read));
        }
        parser.finish();
        return accumulator.finish();
    }
}
