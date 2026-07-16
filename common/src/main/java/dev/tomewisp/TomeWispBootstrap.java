package dev.tomewisp;

import com.google.gson.Gson;
import dev.tomewisp.context.minecraft.MinecraftContextCapture;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.platform.PlatformServices;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.builtin.FindRecipesTool;
import dev.tomewisp.tool.builtin.PlatformInfoTool;
import dev.tomewisp.tool.builtin.PlayerContextTool;
import dev.tomewisp.tool.builtin.ResolveResourceTool;
import dev.tomewisp.trace.json.TraceParser;
import dev.tomewisp.trace.minecraft.TraceReplayService;
import dev.tomewisp.trace.minecraft.TraceRepository;
import dev.tomewisp.trace.replay.AgentTraceReplayer;
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
        tools.register(
                "tomewisp:builtins",
                List.of(
                        new PlatformInfoTool(platform),
                        new ResolveResourceTool(),
                        new FindRecipesTool(),
                        new PlayerContextTool()));
        Gson gson = new Gson();
        TraceReplayService traceReplay = new TraceReplayService(
                new TraceRepository(new TraceParser()),
                new MinecraftContextCapture(gson),
                new AgentTraceReplayer(tools, gson));
        runtime = new TomeWispRuntime(
                platform, tools, new DevelopmentToolInspector(tools), traceReplay);
        TomeWispConstants.LOGGER.info(
                "Initialized TomeWisp on {} with {} tool(s)",
                platform.platformName(),
                tools.descriptors().size());
        return runtime;
    }
}
