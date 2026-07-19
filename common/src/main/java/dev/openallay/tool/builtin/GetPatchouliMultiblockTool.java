package dev.openallay.tool.builtin;

import dev.openallay.context.ToolInvocationContext;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.integration.patchouli.PatchouliMultiblock;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.List;

public final class GetPatchouliMultiblockTool
        implements Tool<GetPatchouliMultiblockTool.Input, GetPatchouliMultiblockTool.Output> {
    public record Input(String structureRef) {}
    public record Output(PatchouliMultiblock multiblock, List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:get_patchouli_multiblock",
            "Read the complete verified block coordinates for an indexed Patchouli structure reference",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);

    private final PatchouliMultiblockStore store;

    public GetPatchouliMultiblockTool(PatchouliMultiblockStore store) {
        this.store = store;
    }

    @Override public ToolDescriptor<Input, Output> descriptor() { return DESCRIPTOR; }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        if (input == null || input.structureRef() == null || input.structureRef().isBlank()) {
            return new ToolResult.Failure<>(
                    "invalid_arguments", "structureRef must not be blank");
        }
        return store.find(input.structureRef())
                .<ToolResult<Output>>map(value -> new ToolResult.Success<>(new Output(
                        value, List.of(value.evidence()))))
                .orElseGet(() -> new ToolResult.Failure<>(
                        "patchouli_multiblock_not_found",
                        "No indexed Patchouli multiblock matches " + input.structureRef()));
    }
}
