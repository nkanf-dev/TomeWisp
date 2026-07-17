package dev.tomewisp.guide;

import dev.tomewisp.tool.ToolResult;

@FunctionalInterface
public interface GuideScreenOpener {
    ToolResult<Boolean> open(GuideService service);
}
