package dev.openallay.fabric;

import dev.openallay.platform.PlatformService;
import dev.openallay.platform.InstalledModMetadata;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    @Override
    public List<InstalledModMetadata> installedMods() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(container -> {
                    var metadata = container.getMetadata();
                    Map<String, String> contacts = new TreeMap<>(metadata.getContact().asMap());
                    List<String> dependencies = metadata.getDependencies().stream()
                            .map(dependency -> dependency.getKind().name().toLowerCase(java.util.Locale.ROOT)
                                    + ":" + dependency.getModId() + ":"
                                    + dependency.getVersionRequirements())
                            .sorted()
                            .toList();
                    return new InstalledModMetadata(
                            metadata.getId(),
                            metadata.getName(),
                            metadata.getVersion().getFriendlyString(),
                            metadata.getDescription(),
                            metadata.getAuthors().stream().map(author -> author.getName()).toList(),
                            metadata.getLicense().stream().sorted().toList(),
                            contacts,
                            metadata.getEnvironment().name().toLowerCase(java.util.Locale.ROOT),
                            dependencies);
                })
                .sorted(Comparator.comparing(InstalledModMetadata::id))
                .toList();
    }
}
