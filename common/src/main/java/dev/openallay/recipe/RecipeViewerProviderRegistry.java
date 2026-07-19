package dev.openallay.recipe;

import dev.openallay.context.RecipeReference;
import dev.openallay.platform.PlatformService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class RecipeViewerProviderRegistry {
    @FunctionalInterface
    public interface Factory {
        RecipeKnowledgeProvider create(Instant capturedAt, PlatformService platform);
    }

    private static final Map<String, Factory> FACTORIES = new TreeMap<>();

    private RecipeViewerProviderRegistry() {}

    public static synchronized void register(String sourceId, Factory factory) {
        FACTORIES.put(
                RecipeReference.requireSourceId(sourceId),
                Objects.requireNonNull(factory, "factory"));
    }

    public static synchronized List<RecipeKnowledgeProvider> providers(
            Instant capturedAt, PlatformService platform) {
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(platform, "platform");
        List<RecipeKnowledgeProvider> providers = new ArrayList<>();
        FACTORIES.values().forEach(factory -> providers.add(factory.create(capturedAt, platform)));
        return List.copyOf(providers);
    }

    public static synchronized List<String> sourceIds() {
        return List.copyOf(FACTORIES.keySet());
    }
}
