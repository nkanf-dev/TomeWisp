package dev.openallay.guide;

import dev.openallay.tool.ToolResult;

@FunctionalInterface
public interface GuideScreenOpener {
    ToolResult<Boolean> open(GuideService service);
}
