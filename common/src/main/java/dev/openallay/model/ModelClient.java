package dev.openallay.model;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface ModelClient {
    CompletableFuture<ModelTurn> complete(
            ModelRequest request,
            Consumer<ModelEvent> events,
            CancellationSignal cancellation);
}
