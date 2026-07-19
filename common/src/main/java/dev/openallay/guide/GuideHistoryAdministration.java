package dev.openallay.guide;

import dev.openallay.tool.ToolResult;
import java.util.concurrent.CompletableFuture;

/** Actor-bound history administration exposed to common settings without raw identities. */
public interface GuideHistoryAdministration {
    CompletableFuture<ToolResult<Boolean>> deleteCurrentHistory();

    CompletableFuture<ToolResult<Boolean>> deleteActorHistory();
}
