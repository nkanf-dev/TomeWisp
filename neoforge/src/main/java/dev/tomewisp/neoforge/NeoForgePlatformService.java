package dev.tomewisp.neoforge;

import dev.tomewisp.platform.PlatformService;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public final class NeoForgePlatformService implements PlatformService {
    @Override
    public String platformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.getCurrent().isProduction();
    }
}
