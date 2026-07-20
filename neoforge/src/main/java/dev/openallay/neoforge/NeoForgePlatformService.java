package dev.openallay.neoforge;

import dev.openallay.platform.PlatformService;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.resource.mod.ModResourceEntry;
import dev.openallay.resource.mod.ModResourceSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
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

    @Override
    public ModResourceSnapshot captureModResources() {
        Instant capturedAt = Instant.now();
        List<ModResourceEntry> entries = new ArrayList<>();
        Map<String, String> diagnostics = new TreeMap<>();
        var files = ModList.get().getModFiles();
        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            captureModFile(files.get(fileIndex), files.size() - fileIndex, entries, diagnostics);
        }
        return ModResourceSnapshot.available(capturedAt, entries, diagnostics);
    }

    private static void captureModFile(
            net.neoforged.neoforgespi.language.IModFileInfo fileInfo,
            int precedence,
            List<ModResourceEntry> entries,
            Map<String, String> diagnostics) {
        List<String> modIds = fileInfo.getMods().stream()
                .map(net.neoforged.neoforgespi.language.IModInfo::getModId)
                .sorted()
                .toList();
        try {
            fileInfo.getFile().getContents().visitContent((logicalPath, resource) -> {
                var location = ModResourceEntry.PublicLocation.parse(logicalPath);
                if (location.isEmpty()) {
                    return;
                }
                for (String modId : modIds) {
                    String sourceId = "neoforge:" + modId + "/modfile/0";
                    try (InputStream input = resource.open()) {
                        entries.add(ModResourceEntry.capture(
                                modId,
                                location.orElseThrow(),
                                input,
                                resource.attributes().size(),
                                precedence,
                                sourceId));
                    } catch (IOException | RuntimeException exception) {
                        diagnostics.put(modId, "one_or_more_public_resources_unavailable");
                    }
                }
            });
        } catch (RuntimeException exception) {
            for (String modId : modIds) {
                diagnostics.put(modId, "public_resource_root_unavailable");
            }
        }
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
