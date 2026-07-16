package dev.tomewisp;

import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.platform.PlatformServices;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.builtin.PlatformInfoTool;
import java.util.List;

public final class TomeWispBootstrap {
    private static TomeWispRuntime runtime;

    private TomeWispBootstrap() {}

    public static synchronized TomeWispRuntime initialize() {
        if (runtime != null) {
            return runtime;
        }

        PlatformService platform = PlatformServices.load();
        ToolRegistry tools = new ToolRegistry();
        tools.register("tomewisp:builtins", List.of(new PlatformInfoTool(platform)));
        runtime = new TomeWispRuntime(platform, tools, new DevelopmentToolInspector(tools));
        TomeWispConstants.LOGGER.info(
                "Initialized TomeWisp on {} with {} tool(s)",
                platform.platformName(),
                tools.descriptors().size());
        return runtime;
    }
}
