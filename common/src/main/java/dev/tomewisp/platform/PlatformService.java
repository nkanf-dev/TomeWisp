package dev.tomewisp.platform;

public interface PlatformService {
    String platformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    default String gameVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().name();
    }
}
