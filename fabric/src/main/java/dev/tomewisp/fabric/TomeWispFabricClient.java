package dev.tomewisp.fabric;

import dev.tomewisp.TomeWispBootstrap;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.client.ClientGuideRuntime;
import dev.tomewisp.tool.ToolResult;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import dev.tomewisp.fabric.network.FabricBridgePayloads;
import dev.tomewisp.fabric.network.FabricClientBridge;

public final class TomeWispFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TomeWispRuntime runtime = TomeWispBootstrap.initialize();
        FabricBridgePayloads.register();
        FabricClientBridge bridge = new FabricClientBridge();
        bridge.register();
        ToolResult<ClientGuideRuntime> guide = ClientGuideRuntime.create(
                runtime,
                FabricLoader.getInstance().getConfigDir().resolve("tomewisp/model.json"),
                System.getenv(),
                runnable -> Minecraft.getInstance().execute(runnable),
                bridge.remoteTools());
        FabricGuideCommands.register(runtime, guide, bridge);
    }
}
