package dev.openallay.platform;

import dev.openallay.resource.mod.ModResourceSnapshot;
import java.time.Instant;
import java.util.List;

public interface PlatformService {
    String platformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    /** Complete public loader metadata, detached and sorted by mod id. */
    default List<InstalledModMetadata> installedMods() {
        throw new UnsupportedOperationException("Installed mod metadata is unavailable");
    }

    /**
     * Captures public logical mod resources into a detached loader-neutral snapshot.
     *
     * <p>Loader implementations must invoke this from an appropriate lifecycle/game thread. The returned
     * snapshot cannot retain loader objects, Minecraft resource objects, or physical paths. A platform that
     * cannot enumerate its public resources through a stable API reports explicit unavailability.</p>
     */
    default ModResourceSnapshot captureModResources() {
        return ModResourceSnapshot.unavailable(Instant.now(), "public_mod_resource_enumeration_unavailable");
    }

    default String gameVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().name();
    }
}
