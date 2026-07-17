package dev.tomewisp.tool.builtin;

import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.context.DataAuthority;
import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Map;

public final class PlatformInfoTool
        implements Tool<PlatformInfoTool.Input, PlatformInfoTool.Output> {
    public record Input() {}

    public record Output(
            String platform,
            String gameVersion,
            boolean developmentEnvironment,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            evidence = List.copyOf(evidence);
        }
    }

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
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        EvidenceMetadata evidence = new EvidenceMetadata(
                DataAuthority.INTEGRATION_API,
                DataCompleteness.COMPLETE,
                context.capturedAt(),
                "tomewisp:platform",
                "tomewisp:platform_service",
                platform.gameVersion(),
                platform.platformName(),
                Map.of(
                        "tomewisp:development_environment",
                        Boolean.toString(platform.isDevelopmentEnvironment())));
        return new ToolResult.Success<>(new Output(
                platform.platformName(),
                platform.gameVersion(),
                platform.isDevelopmentEnvironment(),
                List.of(evidence)));
    }
}
