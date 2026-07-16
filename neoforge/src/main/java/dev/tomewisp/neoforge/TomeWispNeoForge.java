package dev.tomewisp.neoforge;

import dev.tomewisp.TomeWispBootstrap;
import dev.tomewisp.TomeWispConstants;
import dev.tomewisp.TomeWispRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import dev.tomewisp.neoforge.network.NeoForgeBridgePayloads;

@Mod(TomeWispConstants.MOD_ID)
public final class TomeWispNeoForge {
    public TomeWispNeoForge(IEventBus modBus) {
        TomeWispRuntime runtime = TomeWispBootstrap.initialize();
        NeoForgeBridgePayloads.register(modBus, runtime);
        NeoForgeDevelopmentCommands.register(runtime);
        if (FMLEnvironment.getDist().isClient()) {
            TomeWispNeoForgeClient.initialize(runtime, modBus);
        }
    }
}
