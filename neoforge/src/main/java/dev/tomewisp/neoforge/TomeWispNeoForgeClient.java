package dev.tomewisp.neoforge;

import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.client.ClientGuideRuntime;
import dev.tomewisp.tool.ToolResult;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.bus.api.IEventBus;
import dev.tomewisp.neoforge.network.NeoForgeClientBridge;

public final class TomeWispNeoForgeClient {
    private TomeWispNeoForgeClient() {}

    public static void initialize(TomeWispRuntime runtime, IEventBus modBus) {
        NeoForgeClientBridge bridge = new NeoForgeClientBridge();
        bridge.register(modBus);
        ToolResult<ClientGuideRuntime> guide = ClientGuideRuntime.create(
                runtime,
                FMLPaths.CONFIGDIR.get().resolve("tomewisp/model.json"),
                System.getenv(),
                runnable -> Minecraft.getInstance().execute(runnable),
                bridge.remoteTools());
        NeoForgeGuideCommands.register(runtime, guide, bridge);
    }
}
