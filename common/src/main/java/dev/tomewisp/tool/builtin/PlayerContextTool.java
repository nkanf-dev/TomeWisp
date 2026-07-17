package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.PlayerSnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import dev.tomewisp.context.ContextCapability;
import java.util.List;
import java.util.Set;

public final class PlayerContextTool
        implements Tool<PlayerContextTool.Input, PlayerContextTool.Output> {
    public record Input() {}

    public record Output(PlayerSnapshot player, List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:player_context",
            "Return captured player identity, location, and inventory with explicit completeness evidence",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY,
            Set.of(ContextCapability.PLAYER));

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        return context.player()
                .<ToolResult<Output>>map(player -> new ToolResult.Success<>(new Output(
                        player, List.of(player.evidence(), player.inventory().evidence()))))
                .orElseGet(() -> new ToolResult.Failure<>(
                        "player_required", "this tool requires a player invocation context"));
    }
}
