package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.RegistryEntrySnapshot;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.agent.tool.ToolOptional;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Set;
import dev.tomewisp.context.ContextCapability;

public final class ResolveResourceTool
        implements Tool<ResolveResourceTool.Input, ResolveResourceTool.Output> {
    public record Input(String id, @ToolOptional String kind) {}

    public record Match(
            String id, String kind, String displayName, String namespace, String provenance) {}

    public record Output(
            String requestedId,
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
            "Resolve an item or block identifier from the captured Minecraft registries",
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
        if (input == null || !BuiltinToolValidation.isIdentifier(input.id())) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "id must be a valid namespaced Minecraft identifier");
        }

        String kind = input.kind() == null ? "" : input.kind().trim();
        if (!kind.isEmpty() && !kind.equals("item") && !kind.equals("block")) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "kind must be item, block, or blank");
        }
        if (context.registries().isEmpty()) {
            return new ToolResult.Failure<>(
                    "missing_context", "registry context was not captured for this invocation");
        }

        var snapshot = context.registries().orElseThrow();
        List<Match> matches = snapshot.entries().stream()
                .filter(entry -> entry.id().equals(input.id()))
                .filter(entry -> kind.isEmpty() || entry.kind().equals(kind))
                .map(ResolveResourceTool::toMatch)
                .toList();
        return new ToolResult.Success<>(new Output(
                input.id(), !matches.isEmpty(), matches, List.of(snapshot.evidence())));
    }

    private static Match toMatch(RegistryEntrySnapshot entry) {
        return new Match(
                entry.id(),
                entry.kind(),
                entry.displayName(),
                entry.namespace(),
                entry.provenance());
    }
}
