package dev.tomewisp.fabric;

import dev.tomewisp.TomeWispBootstrap;
import dev.tomewisp.TomeWispRuntime;
import net.fabricmc.api.ModInitializer;
import dev.tomewisp.fabric.network.FabricBridgePayloads;
import dev.tomewisp.fabric.network.FabricServerBridge;

public final class TomeWispFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        TomeWispRuntime runtime = TomeWispBootstrap.initialize();
        FabricBridgePayloads.register();
        FabricServerBridge.register(runtime);
        FabricDevelopmentCommands.register(runtime);
    }
}
