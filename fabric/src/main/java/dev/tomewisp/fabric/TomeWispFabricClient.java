package dev.tomewisp.fabric;

import dev.tomewisp.TomeWispBootstrap;
import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.client.ClientGuideRuntime;
import dev.tomewisp.tool.ToolResult;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

public final class TomeWispFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TomeWispRuntime runtime = TomeWispBootstrap.initialize();
        ToolResult<ClientGuideRuntime> guide = ClientGuideRuntime.create(
                runtime,
                FabricLoader.getInstance().getConfigDir().resolve("tomewisp/model.json"),
                System.getenv(),
                runnable -> Minecraft.getInstance().execute(runnable));
        FabricGuideCommands.register(runtime, guide);
    }
}
