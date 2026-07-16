package dev.tomewisp.neoforge;

import dev.tomewisp.TomeWispBootstrap;
import dev.tomewisp.TomeWispConstants;
import dev.tomewisp.TomeWispRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(TomeWispConstants.MOD_ID)
public final class TomeWispNeoForge {
    public TomeWispNeoForge(IEventBus modBus) {
        TomeWispRuntime runtime = TomeWispBootstrap.initialize();
        NeoForgeDevelopmentCommands.register(runtime);
        if (FMLEnvironment.getDist().isClient()) {
            TomeWispNeoForgeClient.initialize(runtime);
        }
    }
}
