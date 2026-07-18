package dev.tomewisp.model.openai;

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
import dev.tomewisp.net.HttpExchangeRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class OpenAiChatClient implements ModelClient {
    private final ModelConfig config;
    private final OpenAiJsonCodec codec;
    private final HttpModelTransport transport;

    public OpenAiChatClient(ModelConfig config, Gson gson) {
        this.config = Objects.requireNonNull(config, "config");
        codec = new OpenAiJsonCodec(gson);
        transport = new HttpModelTransport(config);
    }

    @Override
    public CompletableFuture<ModelTurn> complete(
            ModelRequest request,
            Consumer<ModelEvent> events,
            CancellationSignal cancellation) {
        HttpExchangeRequest httpRequest = HttpExchangeRequest.newBuilder(
                        config.baseUri().resolve("chat/completions"))
                .timeout(config.requestTimeout())
                .header("authorization", "Bearer " + config.apiKey().reveal())
                .header("content-type", "application/json")
                .postJson(codec.requestBody(config, request))
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
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(events);
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
