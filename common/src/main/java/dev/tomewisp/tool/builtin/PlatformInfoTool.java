package dev.tomewisp.tool.builtin;

import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;

public final class PlatformInfoTool
        implements Tool<PlatformInfoTool.Input, PlatformInfoTool.Output> {
    public record Input() {}

    public record Output(String platform, boolean developmentEnvironment) {}

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:platform_info",
            "Return active loader and environment",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);

    private final PlatformService platform;

    public PlatformInfoTool(PlatformService platform) {
        this.platform = platform;
    }

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(Input input) {
        return new ToolResult.Success<>(
                new Output(platform.platformName(), platform.isDevelopmentEnvironment()));
    }
}
