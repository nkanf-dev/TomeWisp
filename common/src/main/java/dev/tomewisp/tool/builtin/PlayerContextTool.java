package dev.tomewisp.tool.builtin;

import dev.tomewisp.context.PlayerSnapshot;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;

public final class PlayerContextTool
        implements Tool<PlayerContextTool.Input, PlayerContextTool.Output> {
    public record Input() {}

    public record Output(PlayerSnapshot player) {}

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:player_context",
            "Return the captured player identity, location, hands, and complete inventory",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        return context.player()
                .<ToolResult<Output>>map(player -> new ToolResult.Success<>(new Output(player)))
                .orElseGet(() -> new ToolResult.Failure<>(
                        "player_required", "this tool requires a player invocation context"));
    }
}
