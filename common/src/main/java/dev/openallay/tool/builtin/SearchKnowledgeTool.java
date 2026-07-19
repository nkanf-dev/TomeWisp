package dev.openallay.tool.builtin;

import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.knowledge.search.KnowledgeSearchResult;
import dev.openallay.knowledge.online.OnlineKnowledgeDiagnostic;
import dev.openallay.knowledge.online.OnlineKnowledgeHit;
import dev.openallay.knowledge.online.OnlineKnowledgeSearch;
import dev.openallay.knowledge.online.OnlineKnowledgeSearchService;
import dev.openallay.model.CancellationSignal;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public final class SearchKnowledgeTool
        implements Tool<SearchKnowledgeTool.Input, SearchKnowledgeTool.Output> {
    public enum Scope { ALL, LOCAL, ONLINE }

    public record Input(
            String query,
            @ToolOptional Integer limit,
            @ToolOptional Scope scope) {
        public Input(String query, Integer limit) {
            this(query, limit, null);
        }
    }
    public record Output(
            String query,
            Scope scope,
            List<KnowledgeSearchResult> results,
            List<OnlineKnowledgeHit> onlineResults,
            List<OnlineKnowledgeDiagnostic> diagnostics,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            results = List.copyOf(results);
            onlineResults = List.copyOf(onlineResults);
            diagnostics = List.copyOf(diagnostics);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:search_knowledge",
            "Search local in-game guides/visible quests and fixed public Minecraft Wiki or MC百科 sources",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);
    private final KnowledgeRegistry registry;
    private final OnlineKnowledgeSearchService online;

    public SearchKnowledgeTool(KnowledgeRegistry registry) {
        this(registry, null);
    }

    public SearchKnowledgeTool(
            KnowledgeRegistry registry, OnlineKnowledgeSearchService online) {
        this.registry = registry;
        this.online = online;
    }

    @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        return local(context, input);
    }

    @Override
    public CompletableFuture<ToolResult<Output>> invokeAsync(
            ToolInvocationContext context, Input input, CancellationSignal cancellation) {
        ToolResult<Output> validated = local(context, input);
        if (validated instanceof ToolResult.Failure<Output> || online == null) {
            return CompletableFuture.completedFuture(validated);
        }
        Scope scope = input.scope() == null ? Scope.ALL : input.scope();
        if (scope == Scope.LOCAL) {
            return CompletableFuture.completedFuture(validated);
        }
        int limit = input.limit() == null ? 20 : input.limit();
        return online.search(input.query(), limit, context, cancellation)
                .thenApply(search -> merge(input, validated, search));
    }

    private ToolResult<Output> local(ToolInvocationContext context, Input input) {
        if (input == null || input.query() == null || input.query().isBlank()) {
            return new ToolResult.Failure<>("invalid_arguments", "query must not be blank");
        }
        try {
            Scope scope = input.scope() == null ? Scope.ALL : input.scope();
            if (scope == Scope.ONLINE && online == null) {
                return new ToolResult.Failure<>(
                        "online_knowledge_unavailable", "Online knowledge sources are unavailable");
            }
            if (scope == Scope.ONLINE) {
                return new ToolResult.Success<>(new Output(
                        input.query(), scope, List.of(), List.of(), List.of(), List.of()));
            }
            var search = registry.search(input.query(), input.limit());
            return new ToolResult.Success<>(new Output(
                    input.query(),
                    scope,
                    search.results(),
                    List.of(),
                    List.of(),
                    search.evidence()));
        } catch (IllegalArgumentException failure) {
            return new ToolResult.Failure<>("invalid_arguments", failure.getMessage());
        }
    }

    private static ToolResult<Output> merge(
            Input input, ToolResult<Output> local, OnlineKnowledgeSearch online) {
        Output base = ((ToolResult.Success<Output>) local).value();
        List<EvidenceMetadata> evidence = new ArrayList<>(base.evidence());
        online.hits().stream().map(OnlineKnowledgeHit::evidence).distinct().forEach(evidence::add);
        Scope scope = input.scope() == null ? Scope.ALL : input.scope();
        if (scope == Scope.ONLINE && online.hits().isEmpty()) {
            return new ToolResult.Failure<>(
                    "online_knowledge_unavailable",
                    "No public knowledge source completed this search");
        }
        if (scope == Scope.ALL
                && base.results().isEmpty()
                && base.evidence().isEmpty()
                && online.hits().isEmpty()) {
            return new ToolResult.Failure<>(
                    "knowledge_unavailable",
                    "No local or public knowledge source completed this search");
        }
        return new ToolResult.Success<>(new Output(
                input.query(),
                scope,
                base.results(),
                online.hits(),
                online.diagnostics(),
                evidence.stream().distinct().toList()));
    }
}
