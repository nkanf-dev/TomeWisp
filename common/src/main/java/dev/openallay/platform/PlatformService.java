package dev.openallay.platform;

import java.util.List;

public interface PlatformService {
    String platformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    /** Complete public loader metadata, detached and sorted by mod id. */
    default List<InstalledModMetadata> installedMods() {
        throw new UnsupportedOperationException("Installed mod metadata is unavailable");
    }

    default String gameVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().name();
    }
}
