package dev.openallay.model.metadata;

import dev.openallay.model.CancellationSignal;
import java.util.concurrent.CompletableFuture;

public interface ModelMetadataResolver {
    CompletableFuture<ModelMetadataResolution> resolve(
            String modelId,
            Integer explicitContextWindowTokens,
            Integer explicitMaxOutputTokens,
            CancellationSignal cancellation);
}
