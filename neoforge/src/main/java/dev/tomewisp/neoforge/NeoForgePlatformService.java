package dev.tomewisp.neoforge;

import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.platform.InstalledModMetadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    @Override
    public List<InstalledModMetadata> installedMods() {
        return ModList.get().getMods().stream()
                .map(mod -> {
                    Map<String, String> contacts = new TreeMap<>();
                    mod.getModURL().ifPresent(url -> contacts.put("homepage", url.toString()));
                    mod.getUpdateURL().ifPresent(url -> contacts.put("update", url.toString()));
                    List<String> authors = configStrings(mod, "authors");
                    List<String> dependencies = mod.getDependencies().stream()
                            .map(dependency -> dependency.getType().name().toLowerCase(java.util.Locale.ROOT)
                                    + ":" + dependency.getModId() + ":"
                                    + dependency.getVersionRange() + ":"
                                    + dependency.getSide().name().toLowerCase(java.util.Locale.ROOT))
                            .sorted()
                            .toList();
                    String license = mod.getOwningFile().getLicense();
                    return new InstalledModMetadata(
                            mod.getModId(),
                            mod.getDisplayName(),
                            mod.getVersion().toString(),
                            mod.getDescription(),
                            authors,
                            license == null || license.isBlank() ? List.of() : List.of(license),
                            contacts,
                            "both",
                            dependencies);
                })
                .sorted(Comparator.comparing(InstalledModMetadata::id))
                .toList();
    }

    private static List<String> configStrings(
            net.neoforged.neoforgespi.language.IModInfo mod, String key) {
        Object value = mod.getConfig().getConfigElement(key).orElse(null);
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        if (value instanceof List<?> values) {
            List<String> result = new ArrayList<>();
            for (Object item : values) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString());
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }
}
