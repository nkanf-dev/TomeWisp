package dev.tomewisp.fabric;

import dev.tomewisp.platform.PlatformService;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricPlatformService implements PlatformService {
    @Override
    public String platformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
