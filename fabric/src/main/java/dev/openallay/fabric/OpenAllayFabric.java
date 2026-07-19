package dev.openallay.fabric;

import dev.openallay.OpenAllayBootstrap;
import dev.openallay.OpenAllayRuntime;
import net.fabricmc.api.ModInitializer;
import dev.openallay.fabric.network.FabricBridgePayloads;
import dev.openallay.fabric.network.FabricServerBridge;

public final class OpenAllayFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        OpenAllayRuntime runtime = OpenAllayBootstrap.initialize();
        FabricBridgePayloads.register();
        FabricServerBridge.register(runtime);
        FabricDevelopmentCommands.register(runtime);
    }
}
