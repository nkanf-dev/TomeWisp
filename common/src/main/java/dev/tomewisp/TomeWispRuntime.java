package dev.tomewisp;

import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.tool.ToolRegistry;

public record TomeWispRuntime(
        PlatformService platform,
        ToolRegistry tools,
        DevelopmentToolInspector developmentTools) {}
