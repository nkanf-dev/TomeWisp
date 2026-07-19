package dev.openallay.neoforge;

import dev.openallay.OpenAllayBootstrap;
import dev.openallay.OpenAllayConstants;
import dev.openallay.OpenAllayRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import dev.openallay.neoforge.network.NeoForgeBridgePayloads;

@Mod(OpenAllayConstants.MOD_ID)
public final class OpenAllayNeoForge {
    public OpenAllayNeoForge(IEventBus modBus) {
        OpenAllayRuntime runtime = OpenAllayBootstrap.initialize();
        NeoForgeBridgePayloads.register(modBus, runtime);
        NeoForgeDevelopmentCommands.register(runtime);
        if (FMLEnvironment.getDist().isClient()) {
            OpenAllayNeoForgeClient.initialize(runtime, modBus);
        }
    }
}
