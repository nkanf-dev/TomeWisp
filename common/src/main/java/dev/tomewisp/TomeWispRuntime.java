package dev.tomewisp;

import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.trace.minecraft.TraceReplayService;

public record TomeWispRuntime(
        PlatformService platform,
        ToolRegistry tools,
        DevelopmentToolInspector developmentTools,
        TraceReplayService traceReplay) {}
