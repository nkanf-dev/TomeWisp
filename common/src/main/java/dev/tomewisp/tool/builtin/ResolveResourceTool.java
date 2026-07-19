package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.RegistryEntrySnapshot;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.agent.tool.ToolOptional;
import dev.tomewisp.agent.tool.ToolDescription;
import dev.tomewisp.agent.tool.ToolPattern;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Comparator;
import java.util.Arrays;
import dev.tomewisp.context.ContextCapability;

public final class ResolveResourceTool
        implements Tool<ResolveResourceTool.Input, ResolveResourceTool.Output> {
    public enum Kind { item, block }

    @ToolDescription("Resolve a player-visible item or block name to exact Minecraft identifiers")
    public record Input(
            @ToolDescription("Localized display name, identifier path, or full namespaced identifier")
            @ToolPattern(".*\\S.*") String query,
            @ToolDescription("Optional resource kind") @ToolOptional Kind kind) {}

    public record Match(
            String id, String kind, String displayName, String namespace, String provenance) {}

    public record Output(
            String requestedQuery,
            boolean exists,
            List<Match> matches,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            matches = List.copyOf(matches);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:resolve_resource",
            "Resolve a localized name, identifier path, or full ID to exact captured item/block IDs",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY,
            Set.of(ContextCapability.REGISTRIES));

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        if (input == null || input.query() == null || input.query().isBlank()) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "query must contain a player-visible resource name or identifier");
        }
        if (context.registries().isEmpty()) {
            return new ToolResult.Failure<>(
                    "missing_context", "registry context was not captured for this invocation");
        }

        var snapshot = context.registries().orElseThrow();
        String query = input.query().trim();
        String normalized = normalize(query);
        boolean exactIdentifier = BuiltinToolValidation.isIdentifier(query);
        List<Match> matches = snapshot.entries().stream()
                .filter(entry -> input.kind() == null || entry.kind().equals(input.kind().name()))
                .map(entry -> new Ranked(entry, rank(entry, query, normalized, exactIdentifier)))
                .filter(ranked -> ranked.rank() >= 0)
                .sorted(Comparator.comparingInt(Ranked::rank)
                        .thenComparing(ranked -> ranked.entry().id())
                        .thenComparing(ranked -> ranked.entry().kind()))
                .map(Ranked::entry)
                .map(ResolveResourceTool::toMatch)
                .toList();
        return new ToolResult.Success<>(new Output(
                query, !matches.isEmpty(), matches, List.of(snapshot.evidence())));
    }

    private static int rank(
            RegistryEntrySnapshot entry,
            String query,
            String normalizedQuery,
            boolean exactIdentifier) {
        if (entry.id().equals(query)) return 0;
        String display = normalize(entry.displayName());
        String path = normalize(entry.id().substring(entry.id().indexOf(':') + 1));
        if (display.equals(normalizedQuery)) return 1;
        if (path.equals(normalizedQuery)) return 2;
        if (exactIdentifier) return -1;
        List<String> tokens = tokens(normalizedQuery);
        if (!tokens.isEmpty() && tokens.stream().allMatch(display::contains)) return 3;
        if (!tokens.isEmpty() && tokens.stream().allMatch(path::contains)) return 4;
        return -1;
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ");
    }

    private static List<String> tokens(String normalized) {
        return Arrays.stream(normalized.split(" "))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private record Ranked(RegistryEntrySnapshot entry, int rank) {}

    private static Match toMatch(RegistryEntrySnapshot entry) {
        return new Match(
                entry.id(),
                entry.kind(),
                entry.displayName(),
                entry.namespace(),
                entry.provenance());
    }
}
