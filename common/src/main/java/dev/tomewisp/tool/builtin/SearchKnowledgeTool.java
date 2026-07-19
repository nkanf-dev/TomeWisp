package dev.tomewisp.tool.builtin;

import dev.tomewisp.agent.tool.ToolOptional;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.knowledge.search.KnowledgeSearchResult;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;

public final class SearchKnowledgeTool
        implements Tool<SearchKnowledgeTool.Input, SearchKnowledgeTool.Output> {
    public record Input(String query, @ToolOptional Integer limit) {}
    public record Output(
            String query,
            List<KnowledgeSearchResult> results,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            results = List.copyOf(results);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:search_knowledge",
            "Search indexed in-game guides and visible quests by resource ID, alias, heading, or text",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);
    private final KnowledgeRegistry registry;

    public SearchKnowledgeTool(KnowledgeRegistry registry) {
        this.registry = registry;
    }

    @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        if (input == null || input.query() == null || input.query().isBlank()) {
            return new ToolResult.Failure<>("invalid_arguments", "query must not be blank");
        }
        try {
            var search = registry.search(input.query(), input.limit());
            return new ToolResult.Success<>(new Output(
                    input.query(),
                    search.results(),
                    search.evidence()));
        } catch (IllegalArgumentException failure) {
            return new ToolResult.Failure<>("invalid_arguments", failure.getMessage());
        }
    }
}
