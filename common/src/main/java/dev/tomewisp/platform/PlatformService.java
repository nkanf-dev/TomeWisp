package dev.tomewisp.platform;

public interface PlatformService {
    String platformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();
}
