package dev.openallay.tool;

import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import java.util.concurrent.CompletableFuture;

public interface Tool<I, O> {
    ToolDescriptor<I, O> descriptor();

    ToolResult<O> invoke(ToolInvocationContext context, I input);

    default CompletableFuture<ToolResult<O>> invokeAsync(
            ToolInvocationContext context, I input, CancellationSignal cancellation) {
        cancellation.throwIfCancelled();
        return CompletableFuture.completedFuture(invoke(context, input));
    }
}
