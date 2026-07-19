package dev.tomewisp.model.metadata;

import dev.tomewisp.model.CancellationSignal;
import java.util.concurrent.CompletableFuture;

public interface ModelMetadataResolver {
    CompletableFuture<ModelMetadataResolution> resolve(
            String modelId,
            Integer explicitContextWindowTokens,
            Integer explicitMaxOutputTokens,
            CancellationSignal cancellation);
}
