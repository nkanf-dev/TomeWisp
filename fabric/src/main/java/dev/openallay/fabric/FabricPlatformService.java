package dev.openallay.fabric;

import dev.openallay.platform.PlatformService;
import dev.openallay.platform.InstalledModMetadata;
import dev.openallay.resource.mod.ModResourceEntry;
import dev.openallay.resource.mod.ModResourceSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
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

    @Override
    public ModResourceSnapshot captureModResources() {
        Instant capturedAt = Instant.now();
        List<ModResourceEntry> entries = new ArrayList<>();
        Map<String, String> diagnostics = new TreeMap<>();
        FabricLoader.getInstance().getAllMods().stream()
                .sorted(Comparator.comparing(container -> container.getMetadata().getId()))
                .forEach(container -> captureContainer(container, entries, diagnostics));
        return ModResourceSnapshot.available(capturedAt, entries, diagnostics);
    }

    private static void captureContainer(
            net.fabricmc.loader.api.ModContainer container,
            List<ModResourceEntry> entries,
            Map<String, String> diagnostics) {
        String modId = container.getMetadata().getId();
        List<Path> roots = container.getRootPaths();
        for (int rootIndex = 0; rootIndex < roots.size(); rootIndex++) {
            Path root = roots.get(rootIndex);
            int precedence = roots.size() - rootIndex;
            String sourceId = "fabric:" + modId + "/root/" + rootIndex;
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .sorted(Comparator.comparing(path -> logicalPath(root.relativize(path))))
                        .forEach(path -> capturePath(
                                modId, root, path, precedence, sourceId, entries, diagnostics));
            } catch (IOException | RuntimeException exception) {
                diagnostics.put(modId, "public_resource_root_unavailable");
            }
        }
    }

    private static void capturePath(
            String modId,
            Path root,
            Path path,
            int precedence,
            String sourceId,
            List<ModResourceEntry> entries,
            Map<String, String> diagnostics) {
        String logicalPath = logicalPath(root.relativize(path));
        var location = ModResourceEntry.PublicLocation.parse(logicalPath);
        if (location.isEmpty()) {
            return;
        }
        try (InputStream input = Files.newInputStream(path)) {
            entries.add(ModResourceEntry.capture(
                    modId, location.orElseThrow(), input, Files.size(path), precedence, sourceId));
        } catch (IOException | RuntimeException exception) {
            diagnostics.put(modId, "one_or_more_public_resources_unavailable");
        }
    }

    private static String logicalPath(Path relative) {
        StringBuilder result = new StringBuilder();
        for (Path segment : relative) {
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(segment);
        }
        return result.toString();
    }
}
