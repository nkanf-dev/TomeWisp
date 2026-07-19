package dev.openallay.tool.builtin;

import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.knowledge.online.OnlineKnowledgeDocument;
import dev.openallay.knowledge.online.OnlineKnowledgeSearchService;
import dev.openallay.model.CancellationSignal;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Opens only an opaque document reference previously issued by a fixed online source. */
public final class ReadOnlineKnowledgeTool
        implements Tool<ReadOnlineKnowledgeTool.Input, ReadOnlineKnowledgeTool.Output> {
    public record Input(String sourceId, String reference) {}
    public record Output(
            String sourceId, String title, String body, String reference,
            List<EvidenceMetadata> evidence) implements EvidenceBearing {
        public Output { evidence = List.copyOf(evidence); }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:read_online_knowledge",
            "Read one fixed-origin public knowledge article returned by search_knowledge; pass its sourceId and opaque reference",
            Input.class, Output.class, ToolAccess.READ_ONLY);
    private final OnlineKnowledgeSearchService service;

    public ReadOnlineKnowledgeTool(OnlineKnowledgeSearchService service) {
        this.service = java.util.Objects.requireNonNull(service, "service");
    }

    @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        return new ToolResult.Failure<>("async_required", "Online articles are read asynchronously");
    }

    @Override
    public CompletableFuture<ToolResult<Output>> invokeAsync(
            ToolInvocationContext context, Input input, CancellationSignal cancellation) {
        if (input == null || input.sourceId() == null || input.sourceId().isBlank()
                || input.reference() == null || input.reference().isBlank()) {
            return CompletableFuture.completedFuture(new ToolResult.Failure<>(
                    "invalid_arguments", "sourceId and reference are required"));
        }
        return service.fetch(input.sourceId(), input.reference(), context, cancellation)
                .<ToolResult<Output>>thenApply(document -> success(document))
                .exceptionally(failure -> new ToolResult.Failure<>(
                        "online_document_unavailable",
                        "The selected public knowledge article is temporarily unavailable"));
    }

    private static ToolResult<Output> success(OnlineKnowledgeDocument document) {
        return new ToolResult.Success<>(new Output(
                document.sourceId(), document.title(), document.body(), document.reference(),
                List.of(document.evidence())));
    }
}
